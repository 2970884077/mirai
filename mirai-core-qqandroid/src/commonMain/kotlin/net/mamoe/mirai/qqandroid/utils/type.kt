/*
 * Copyright 2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

@file:JvmName("Utils")
@file:JvmMultifileClass

package net.mamoe.mirai.qqandroid.utils

import net.mamoe.mirai.message.data.Image
import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.message.data.QuoteReply
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName


internal fun Int.toIpV4AddressString(): String {
    @Suppress("NAME_SHADOWING")
    var var0 = this.toLong() and 0xFFFFFFFF
    return buildString {
        for (var2 in 3 downTo 0) {
            append(255L and var0 % 256L)
            var0 /= 256L
            if (var2 != 0) {
                append('.')
            }
        }
    }
}

internal fun String.chineseLength(upTo: Int): Int {
    return this.sumUpTo(upTo) { if (it in '\u0391'..'\uFFE5') 3 else 1 }
}

internal fun MessageChain.estimateLength(upTo: Int = Int.MAX_VALUE): Int =
    sumUpTo(upTo) { it, up ->
        when (it) {
            is QuoteReply -> 700
            is Image -> 300
            is PlainText -> it.stringValue.chineseLength(up)
            else -> it.toString().chineseLength(up)
        }
    }

internal inline fun <T> Iterable<T>.sumUpTo(upTo: Int, selector: (T, remaining: Int) -> Int): Int {
    var sum = 0
    for (element in this) {
        if (sum >= upTo) {
            return sum
        }
        sum += selector(element, (upTo - sum).coerceAtLeast(0))
    }
    return sum
}

internal inline fun CharSequence.sumUpTo(upTo: Int, selector: (Char) -> Int): Int {
    var sum: Int = 0
    for (element in this) {
        sum += selector(element)
        if (sum >= upTo) {
            return sum
        }
    }
    return sum
}
