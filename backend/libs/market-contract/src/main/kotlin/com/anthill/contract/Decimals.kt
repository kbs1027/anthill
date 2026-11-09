package com.anthill.contract

import java.math.BigDecimal

/** 업비트는 같은 값을 다른 자릿수로 준다(`41983565546.39229000` vs `…3922900000000000`). 비교는 항상 값 기준. */
object Decimals {
    fun same(a: BigDecimal, b: BigDecimal): Boolean = a.compareTo(b) == 0
    fun format(x: BigDecimal): String = x.stripTrailingZeros().toPlainString()
}
