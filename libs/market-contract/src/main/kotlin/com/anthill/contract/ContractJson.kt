package com.anthill.contract

import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal
import java.time.Instant

/**
 * 서비스 사이·브라우저로 오가는 JSON 규칙.
 * 숫자(BigDecimal)는 항상 문자열로 쓰고, 읽을 때는 문자열과 숫자 모두 정확한 값으로 받는다.
 */
object ContractJson {

    fun mapper(): JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .addModule(contractModule())
        .build()

    fun contractModule(): SimpleModule = SimpleModule("anthill-contract")
        .addSerializer(BigDecimal::class.java, stringSerializer { Decimals.format(it) })
        .addDeserializer(BigDecimal::class.java, object : ValueDeserializer<BigDecimal>() {
            override fun deserialize(p: JsonParser, ctxt: DeserializationContext): BigDecimal =
                if (p.currentToken() == JsonToken.VALUE_STRING) BigDecimal(p.string) else p.decimalValue
        })
        .addSerializer(Instant::class.java, stringSerializer { it.toString() })
        .addDeserializer(Instant::class.java, stringDeserializer { Instant.parse(it) })
        .addSerializer(InstrumentId::class.java, stringSerializer { it.value })
        .addDeserializer(InstrumentId::class.java, stringDeserializer { InstrumentId.parse(it) })
        .addSerializer(CandleUnit::class.java, stringSerializer { it.code })
        .addDeserializer(CandleUnit::class.java, stringDeserializer { requireNotNull(CandleUnit.fromCode(it)) { "unknown unit $it" } })
        .addSerializer(CandleState::class.java, stringSerializer { it.name.lowercase() })
        .addDeserializer(CandleState::class.java, stringDeserializer { CandleState.valueOf(it.uppercase()) })

    private fun <T : Any> stringSerializer(write: (T) -> String) = object : ValueSerializer<T>() {
        override fun serialize(value: T, gen: JsonGenerator, ctxt: SerializationContext) {
            gen.writeString(write(value))
        }
    }

    private fun <T : Any> stringDeserializer(read: (String) -> T) = object : ValueDeserializer<T>() {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): T = read(p.string)
    }
}
