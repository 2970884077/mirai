/*
 * Copyright 2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */
@file: OptIn(MiraiExperimentalAPI::class, MiraiInternalAPI::class, LowLevelAPI::class, ExperimentalUnsignedTypes::class)
@file:Suppress("EXPERIMENTAL_API_USAGE")

package net.mamoe.mirai.qqandroid.message

import kotlinx.io.core.discardExact
import kotlinx.io.core.readUInt
import kotlinx.io.core.toByteArray
import net.mamoe.mirai.LowLevelAPI
import net.mamoe.mirai.contact.Member
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.qqandroid.io.serialization.loadAs
import net.mamoe.mirai.qqandroid.io.serialization.toByteArray
import net.mamoe.mirai.qqandroid.network.protocol.data.proto.HummerCommelem
import net.mamoe.mirai.qqandroid.network.protocol.data.proto.ImMsgBody
import net.mamoe.mirai.qqandroid.network.protocol.data.proto.MsgComm
import net.mamoe.mirai.utils.*
import net.mamoe.mirai.utils.io.encodeToString
import net.mamoe.mirai.utils.io.hexToBytes
import net.mamoe.mirai.utils.io.read


private val UNSUPPORTED_MERGED_MESSAGE_PLAIN = PlainText("你的QQ暂不支持查看[转发多条消息]，请期待后续版本。")
private val UNSUPPORTED_POKE_MESSAGE_PLAIN = PlainText("[戳一戳]请使用最新版手机QQ体验新功能。")

@OptIn(MiraiInternalAPI::class, MiraiExperimentalAPI::class)
internal fun MessageChain.toRichTextElems(forGroup: Boolean, withGeneralFlags: Boolean): MutableList<ImMsgBody.Elem> {
    val elements = mutableListOf<ImMsgBody.Elem>()

    if (this.any<QuoteReply>()) {
        when (val source = this[QuoteReply].source) {
            is MessageSourceFromServer -> elements.add(ImMsgBody.Elem(srcMsg = source.delegate))
            is MessageSourceFromMsg -> elements.add(ImMsgBody.Elem(srcMsg = source.toJceData()))
            is MessageSourceFromSend -> elements.add(ImMsgBody.Elem(srcMsg = source.toJceData()))
            else -> error("unsupported MessageSource implementation: ${source::class.simpleName}")
        }
    }

    var longTextResId: String? = null

    fun transformOneMessage(it: Message) {
        if (it is RichMessage) {
            val content = MiraiPlatformUtils.zip(it.content.toByteArray())
            when (it) {
                is LongMessage -> {
                    check(longTextResId == null) { "There must be no more than one LongMessage element in the message chain" }
                    elements.add(
                        ImMsgBody.Elem(
                            richMsg = ImMsgBody.RichMsg(
                                serviceId = 35, // ok
                                template1 = byteArrayOf(1) + content
                            )
                        )
                    )
                    transformOneMessage(UNSUPPORTED_MERGED_MESSAGE_PLAIN)
                    longTextResId = it.resId
                }
                is LightApp -> elements.add(
                    ImMsgBody.Elem(
                        lightApp = ImMsgBody.LightAppElem(
                            data = byteArrayOf(1) + content
                        )
                    )
                )
                else -> elements.add(
                    ImMsgBody.Elem(
                        richMsg = ImMsgBody.RichMsg(
                            serviceId = when (it) {
                                is XmlMessage -> 60
                                is JsonMessage -> 1
                                //   is MergedForwardedMessage -> 35
                                else -> error("unsupported RichMessage: ${it::class.simpleName}")
                            },
                            template1 = byteArrayOf(1) + content
                        )
                    )
                )
            }
        }

        when (it) {
            is PlainText -> elements.add(ImMsgBody.Elem(text = ImMsgBody.Text(str = it.stringValue)))
            is At -> {
                elements.add(ImMsgBody.Elem(text = it.toJceData()))
                elements.add(ImMsgBody.Elem(text = ImMsgBody.Text(str = " ")))
            }
            is PokeMessage -> {
                elements.add(
                    ImMsgBody.Elem(
                        commonElem = ImMsgBody.CommonElem(
                            serviceType = 2,
                            businessType = it.type,
                            pbElem = HummerCommelem.MsgElemInfoServtype2(
                                pokeType = it.type,
                                vaspokeId = it.id
                            ).toByteArray(HummerCommelem.MsgElemInfoServtype2.serializer())
                        )
                    )
                )
                transformOneMessage(UNSUPPORTED_POKE_MESSAGE_PLAIN)
            }
            is OfflineGroupImage -> elements.add(ImMsgBody.Elem(customFace = it.toJceData()))
            is OnlineGroupImageImpl -> elements.add(ImMsgBody.Elem(customFace = it.delegate))
            is OnlineFriendImageImpl -> elements.add(ImMsgBody.Elem(notOnlineImage = it.delegate))
            is OfflineFriendImage -> elements.add(ImMsgBody.Elem(notOnlineImage = it.toJceData()))
            is AtAll -> elements.add(atAllData)
            is Face -> elements.add(ImMsgBody.Elem(face = it.toJceData()))
            is QuoteReplyToSend -> {
                if (forGroup) {
                    check(it is QuoteReplyToSend.ToGroup) {
                        "sending a quote to group using QuoteReplyToSend.ToFriend is prohibited"
                    }
                    if (it.sender is Member) {
                        transformOneMessage(it.createAt())
                    }
                    transformOneMessage(PlainText(" "))
                }
            }
            is QuoteReply, // already transformed above
            is MessageSource, // mirai only
            is RichMessage, // already transformed above
            -> {

            }
            else -> error("unsupported message type: ${it::class.simpleName}")
        }
    }
    this.forEach(::transformOneMessage)

    if (withGeneralFlags) {
        when {
            longTextResId != null -> {
                elements.add(
                    ImMsgBody.Elem(
                        generalFlags = ImMsgBody.GeneralFlags(
                            longTextFlag = 1,
                            longTextResid = longTextResId!!,
                            pbReserve = "78 00 F8 01 00 C8 02 00".hexToBytes()
                        ),
                    )
                )
            }
            this.any<RichMessage>() -> {
                // 08 09 78 00 A0 01 81 DC 01 C8 01 00 F0 01 00 F8 01 00 90 02 00 98 03 00 A0 03 20 B0 03 00 C0 03 00 D0 03 00 E8 03 00 8A 04 02 08 03 90 04 80 80 80 10 B8 04 00 C0 04 00
                elements.add(ImMsgBody.Elem(generalFlags = ImMsgBody.GeneralFlags(pbReserve = PB_RESERVE_FOR_RICH_MESSAGE)))
            }
            else -> elements.add(ImMsgBody.Elem(generalFlags = ImMsgBody.GeneralFlags(pbReserve = PB_RESERVE_FOR_ELSE)))
        }
    }

    return elements
}

private val PB_RESERVE_FOR_RICH_MESSAGE =
    "08 09 78 00 C8 01 00 F0 01 00 F8 01 00 90 02 00 C8 02 00 98 03 00 A0 03 20 B0 03 00 C0 03 00 D0 03 00 E8 03 00 8A 04 02 08 03 90 04 80 80 80 10 B8 04 00 C0 04 00".hexToBytes()
private val PB_RESERVE_FOR_ELSE = "78 00 F8 01 00 C8 02 00".hexToBytes()

@OptIn(ExperimentalUnsignedTypes::class, MiraiInternalAPI::class)
internal fun MsgComm.Msg.toMessageChain(): MessageChain {
    val elements = this.msgBody.richText.elems

    return buildMessageChain(elements.size + 1) {
        +MessageSourceFromMsg(delegate = this@toMessageChain)
        elements.joinToMessageChain(this)
    }.cleanupRubbishMessageElements()
}

// These two functions have difference method signature, don't combine.

@OptIn(ExperimentalUnsignedTypes::class, MiraiInternalAPI::class)
internal fun ImMsgBody.SourceMsg.toMessageChain(): MessageChain {
    val elements = this.elems!!

    return buildMessageChain(elements.size + 1) {
        +MessageSourceFromServer(delegate = this@toMessageChain)
        elements.joinToMessageChain(this)
    }.cleanupRubbishMessageElements()
}

private fun MessageChain.cleanupRubbishMessageElements(): MessageChain {
    var last: SingleMessage? = null
    return buildMessageChain(initialSize = this.count()) {
        this@cleanupRubbishMessageElements.forEach { element ->
            if (last is LongMessage && element is PlainText) {
                if (element == UNSUPPORTED_MERGED_MESSAGE_PLAIN) {
                    last = element
                    return@forEach
                }
            }
            if (last is PokeMessage && element is PlainText) {
                if (element == UNSUPPORTED_POKE_MESSAGE_PLAIN) {
                    last = element
                    return@forEach
                }
            }

            add(element)
            last = element
        }
    }
}

internal inline fun <reified R> Iterable<*>.firstIsInstance(): R {
    this.forEach {
        if (it is R) {
            return it
        }
    }
    throw NoSuchElementException("Collection contains no element matching the predicate.")
}

@OptIn(MiraiInternalAPI::class, LowLevelAPI::class)
internal fun List<ImMsgBody.Elem>.joinToMessageChain(message: MessageChainBuilder) {
    // (this._miraiContentToString())
    this.forEach {
        when {
            it.srcMsg != null -> message.add(QuoteReply(MessageSourceFromServer(it.srcMsg)))
            it.notOnlineImage != null -> message.add(OnlineFriendImageImpl(it.notOnlineImage))
            it.customFace != null -> message.add(OnlineGroupImageImpl(it.customFace))
            it.face != null -> message.add(Face(it.face.index))
            it.text != null -> {
                if (it.text.attr6Buf.isEmpty()) {
                    message.add(it.text.str.toMessage())
                } else {
                    val id: Long
                    it.text.attr6Buf.read {
                        discardExact(7)
                        id = readUInt().toLong()
                    }
                    if (id == 0L) {
                        message.add(AtAll)
                    } else {
                        message.add(At._lowLevelConstructAtInstance(id, it.text.str))
                    }
                }
            }
            it.lightApp != null -> {
                val content = MiraiPlatformUtils.unzip(it.lightApp.data, 1).encodeToString()
                message.add(LightApp(content))
            }
            it.richMsg != null -> {
                val content = MiraiPlatformUtils.unzip(it.richMsg.template1, 1).encodeToString()
                when (it.richMsg.serviceId) {
                    1 -> message.add(JsonMessage(content))
                    60 -> message.add(XmlMessage(content))
                    35 -> message.add(
                        LongMessage(
                            content,
                            this.firstIsInstance<ImMsgBody.GeneralFlags>().longTextResid
                        )
                    )
                    else -> {
                        @Suppress("DEPRECATION")
                        MiraiLogger.debug {
                            "unknown richMsg.serviceId: ${it.richMsg.serviceId}, content=${it.richMsg.template1.contentToString()}, \ntryUnzip=${content}"
                        }
                    }
                }
            }
            it.elemFlags2 != null
                    || it.extraInfo != null
                    || it.generalFlags != null -> {

            }
            it.commonElem != null -> {
                when (it.commonElem.serviceType) {
                    2 -> {
                        val proto = it.commonElem.pbElem.loadAs(HummerCommelem.MsgElemInfoServtype2.serializer())
                        message.add(PokeMessage(proto.pokeType, proto.vaspokeId))
                    }
                }
            }
            else -> {
                // println(it._miraiContentToString())
            }
        }
    }

}