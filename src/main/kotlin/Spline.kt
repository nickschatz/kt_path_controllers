import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.ArrayRealVector
import org.apache.commons.math3.linear.LUDecomposition
import org.apache.commons.math3.optim.univariate.BrentOptimizer
import org.apache.commons.math3.optim.univariate.UnivariateOptimizer
import java.lang.IllegalArgumentException
import kotlin.math.*


class Spline(val waypoints: Array<Pose>): Path() {
    override fun closestTOnPathTo(r: Vector2D, guess: Double): Double {
        return closestTOnPathToArcs(r)
    }
    fun closestTOnPathToGradient(r: Vector2D, guess: Double): Double {
        val t = minimize({t: Double -> calculatePoint(t).sqDist(r)}, guess)
        return t
    }
    fun closestTOnPathToArcs(r: Vector2D): Double {
        var minDist = Double.MAX_VALUE
        var minT: Double? = null
        polynomials.forEach {
            try {
                val testT = it.project(r)
                val testPt = it.eval(testT)
                val testDist = testPt.sqDist(r)
                if (testDist < minDist) {
                    minDist = testDist
                    minT = testT
                }
            }
            catch (e: IllegalArgumentException) {
                // Do nothing, point outside projection domain
            }
        }
        if (minT == null) {
            throw IllegalArgumentException("Point outside projection domain")
        }
        return minT as Double
    }
    fun getPartFor(t: Double): Part {
        polynomials.forEach {
            if (t in it) {
                return it
            }
        }
        throw IllegalArgumentException("t outside domain")
    }

    override fun calculatePoint(t: Double): Vector2D {
        return getPartFor(t).eval(t)
    }

    override fun tangentVec(t: Double): Vector2D {
        return getPartFor(t).tangentVec(t)
    }

    override fun levelSet(r: Vector2D, closestT: Double): Double {
        val pathPt = calculatePoint(closestT)
        val pathTangentVec = tangentVec(closestT)
        val ptPathVec = (r - pathPt).normalized().neg()
        val sgn = sign(pathTangentVec.zProd(ptPathVec))
        return sgn * r.dist(pathPt)
    }

    override fun errorGradient(r: Vector2D, closestT: Double): Vector2D {
        return nVec(r, closestT)
    }

    override fun nVec(r: Vector2D, closestT: Double): Vector2D {
        return tangentVec(closestT).getRightNormal()
    }

    val length: Double
    val polynomials: Array<Part>

    init {
        val mat = Array2DRowRealMatrix(arrayOf(
                doubleArrayOf( 1.0,  1.0, 1.0, 1.0, 1.0, 1.0),
                doubleArrayOf( 0.0,  0.0, 0.0, 0.0, 0.0, 1.0),
                doubleArrayOf( 5.0,  4.0, 3.0, 2.0, 1.0, 0.0),
                doubleArrayOf( 0.0,  0.0, 0.0, 0.0, 1.0, 0.0),
                doubleArrayOf(20.0, 12.0, 6.0, 2.0, 0.0, 0.0),
                doubleArrayOf( 0.0,  0.0, 0.0, 2.0, 0.0, 0.0)
        ))
        polynomials = (arrayOfNulls<Part>(waypoints.size - 1) as Array<Part>)
        for (i in 0 until polynomials.size) {
            val wp1 = waypoints[i]
            val wp2 = waypoints[i+1]

            fun reticHalf(a: Double, b: Double, tangentA: Double, tangentB: Double): Polynomial {
                val vecB = ArrayRealVector(doubleArrayOf(b, a, tangentB, tangentA, 0.0, 0.0))
                val solver = LUDecomposition(mat).solver
                val coeffVec = solver.solve(vecB)
                return Polynomial(coeffVec.toArray())
            }
            polynomials[i] = Part(reticHalf(wp1.x, wp2.x, cos(wp1.heading), cos(wp2.heading)),
                                  reticHalf(wp1.y, wp2.y, sin(wp1.heading), sin(wp2.heading)))
        }
        length = polynomials.sumByDouble { it.length }
        var lenAccum = 0.0
        polynomials.forEach {
            it.beginS = lenAccum
            lenAccum += it.length / length
            it.endS = lenAccum
        }


    }
    class Part(private val xPoly: Polynomial, private val yPoly: Polynomial) {
        val arcs: Array<Biarc.BiarcPartWrapper>
        val length: Double
        var beginS: Double = 0.0
        var endS: Double = 1.0
        init {
            // Find the length of the spline part
            val maxDK = 0.1
            val maxLen = 0.1
            fun subdivide(tBegin: Double, tEnd: Double): Array<Biarc.ArcSegment> {
                val tMid = (tEnd + tBegin) / 2.0
                val pBegin = eval(tBegin)
                val pMid = eval(tMid)
                val pEnd = eval(tEnd)

                if (Vector2D.arePointsCollinear(pBegin, pMid, pEnd)) {
                    return subdivide(tBegin, tMid) + subdivide(tMid, tEnd)
                }

                val arc = Biarc.ArcSegment.fromThreePoints(pBegin, pMid, pEnd)
                val doSubdivide = arc.length() > maxLen ||
                                  (curvature(tEnd) - curvature(tBegin)).absoluteValue > maxDK


                if (doSubdivide) {
                    return subdivide(tBegin, tMid) + subdivide(tMid, tEnd)
                }
                return arrayOf(arc)
            }

            arcs = subdivide(0.0, 1.0).map { Biarc.BiarcPartWrapper(it, 0.0, 1.0) }.toTypedArray()
            length = arcs.sumByDouble { it.length() }
            var lenAccum = 0.0
            arcs.forEachIndexed { index: Int, arc: Biarc.BiarcPartWrapper ->
                arc.begin = lenAccum
                val len_ = arc.length() / length
                arc.end = arc.begin + len_
                lenAccum += len_
            }
        }
        private fun getRealS(s: Double): Double {
            return invLerp(beginS, endS, s)
        }
        fun curvature(s: Double): Double {
            val s_ = getRealS(s)
            return (xPoly.derivative(s_) * yPoly.secondDerivative(s_) - yPoly.derivative(s_) * xPoly.secondDerivative(s_)) /
                    (xPoly.derivative(s_).pow(2) + yPoly.derivative(s_).pow(2)).pow(1.5)
        }
        fun eval(s: Double): Vector2D {
            val s_ = getRealS(s)
            return Vector2D(xPoly.calculate(s_), yPoly.calculate(s_))
        }
        fun project(r: Vector2D): Double {
            var minDist = Double.MAX_VALUE
            var minT: Double? = null
            arcs.forEach {
                try {
                    val testT = it.project(r)
                    val testPt = it.r(testT)
                    val testDist = testPt.sqDist(r)
                    if (testDist < minDist) {
                        minDist = testDist
                        minT = testT
                    }
                }
                catch (e: IllegalArgumentException) {
                    // Do nothing, point outside projection domain
                }
            }
            if (minT == null) {
                throw IllegalArgumentException("Point outside projection domain")
            }
            return lerp(beginS, endS, minT as Double)

        }

        operator fun contains(t: Double): Boolean {
            return t in beginS..endS
        }

        fun tangentVec(t: Double): Vector2D {
            val s = getRealS(t)
            return Vector2D(xPoly.derivative(s), yPoly.derivative(s)).normalized()
        }
    }
}