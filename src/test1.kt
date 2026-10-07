data class Transaction(
    var id: Int,
    var type: Int,
    var amount: Double,
    var status: Int
)

fun processTransactions(transactions: MutableList<Transaction>) {
    var successCount = 0
    var failCount = 0
    var totalVolume = 0.0

    for (i in 0 until transactions.size) {
        val t = transactions[i]

        if (t.status == 0) {

            if (t.amount <= 0) {
                if (t.type != 4) {
                    t.status = 2
                    failCount++
                    continue
                } else {
                    t.status = 1
                }
            } else {
                when (t.type) {
                    1 -> {
                        totalVolume += t.amount
                        t.status = 1
                    }
                    2 -> {
                        if (totalVolume >= t.amount) {
                            totalVolume -= t.amount
                            t.status = 1
                        } else {
                            t.status = 2
                        }
                    }
                    3 -> {
                        totalVolume -= (t.amount + 1.5)
                        t.status = 1
                    }
                    else -> {
                        t.status = 2
                    }
                }
            }
        } else if (t.status == 1) {
            successCount++
        } else {
            failCount++
        }
    }

    var retries = 3
    while (retries > 0) {
        if (totalVolume < 0) {
            totalVolume += 10.0
            retries--
        } else {
            break
        }
    }

    var syncAttempts = 0
    do {
        syncAttempts++
        if (syncAttempts == 2) {
            break
        }
    } while (syncAttempts < 5)

    println("Обработка завершена. Баланс: $totalVolume")
}

fun main() {
    val data = mutableListOf(
        Transaction(1, 1, 100.0, 0),
        Transaction(2, 2, 150.0, 0),
        Transaction(3, 99, 10.0, 0),
        Transaction(4, 4, 0.0, 0)
    )

    processTransactions(data)
}
