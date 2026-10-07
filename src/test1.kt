package test

class BranchingDemo {

    fun classify(x: Int, y: Int): String {
        if (x > 0) {
            if (y > 0) {
                if (x > y) { return "x>y>0" } else {
                    for (i: Int in 0 until y) {
                        when (i) {
                            3 -> return "x"
                            4 -> return "y"
                            5 -> return "x"
                            6 -> return "y"
                        }
                    }
                }
            } else return "x>0, y<=0"
        } else if (x == 0) {
            return "x=0"
        } else {
            return "x<0"
        }
        return "g"
    }

    fun elvis(value: String?): Int {
        val len = value?.length ?: 0
        return if (len > 0) len else -1
    }

    fun whenDemo(x: Int, a: Int, b: Int): String {

        when (x) {
            1, 2, 3 -> println("мало")
            in 4..10 -> { println("средне"); println("ок") }
            !in 0..100 -> println("вне")
            else -> println("много")
        }
        return when {
            a > b -> "a>b"
            a < b -> "a<b"
            else -> "a==b"
        }
    }

    fun loops(n: Int) {
        var i = 0
        while (i < n) {
            if (i % 2 == 0) println("even $i")
            i++
        }

        var k = 0
        do {
            if (k % 3 == 0) println("tri $k")
            k++
        } while (k < n)
    }
}

fun main() {
    val d = BranchingDemo()
    println(d.classify(3, 2))
    println(d.whenDemo(5, 3, 7))
    d.loops(3)
}