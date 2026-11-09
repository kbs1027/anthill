package com.anthill.marketdata.leader

import com.anthill.contract.Candle
import com.anthill.marketdata.live.ClosedCandleSink
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Level
import java.util.logging.Logger

/** [holder]가 [ttl] 동안 임차권을 잡으면 true. 다른 쪽이 유효하게 쥐고 있으면 false, DB에 닿지 않으면 예외. */
fun interface LeaseStore {
    fun tryAcquire(holder: String, ttl: Duration): Boolean
}

/** 임차권 한 줄을 조건부 upsert로 다룬다. 쿼리 하나라 동시에 둘이 이길 수 없다. */
class LeaderLease(private val jdbc: JdbcClient, private val name: String = "marketdata") : LeaseStore {
    override fun tryAcquire(holder: String, ttl: Duration): Boolean = jdbc.sql(
        """
        insert into leader_lease (name, holder, until_at) values (:name, :holder, now() + (:ms * interval '1 millisecond'))
        on conflict (name) do update set holder = excluded.holder, until_at = excluded.until_at
        where leader_lease.holder = excluded.holder or leader_lease.until_at < now()
        """,
    ).param("name", name).param("holder", holder).param("ms", ttl.toMillis()).update() == 1
}

/**
 * marketdata 두 대 중 누가 저장·발행·검사를 맡는지 정한다. 둘 다 시세를 계산해 두고, 주 서버가 죽으면 예비가 [ttl] 안에 이어받는다.
 * 임차권을 제때 갱신하지 못한 주 서버(멈췄다 깨어난 프로세스)는 스스로 물러난다.
 * 단, DB가 안 닿는 동안에는 지금 역할을 유지한다: DB가 잠깐 끊겼다고 차트가 멈추면 안 된다.
 */
class Leadership(
    private val store: LeaseStore,
    private val holder: String,
    private val ttl: Duration,
    private val nanos: () -> Long = System::nanoTime,
) {
    private val log = Logger.getLogger(Leadership::class.java.name)
    private val promoted = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var leader = false
    @Volatile private var storeDown = false
    @Volatile private var validUntil = 0L

    fun isLeader(): Boolean = leader && (storeDown || nanos() - validUntil < 0)

    fun onPromoted(listener: () -> Unit) { promoted += listener }

    fun tick() {
        val before = nanos()
        val won = try {
            store.tryAcquire(holder, ttl)
        } catch (e: Exception) {
            if (!storeDown) log.log(Level.WARNING, "leader lease store unreachable, keeping role leader=$leader", e)
            storeDown = true
            return
        }
        storeDown = false
        when {
            won && !leader -> {
                validUntil = before + ttl.toNanos()
                leader = true
                log.info("$holder is now the leader")
                promoted.forEach { it() }
            }
            won -> validUntil = before + ttl.toNanos()
            leader -> {
                leader = false
                log.warning("$holder lost the leader lease")
            }
        }
    }

    companion object {
        /** 임차권 없이 언제나 주 서버(작업이 꺼진 프로필·테스트용). */
        fun always() = Leadership({ _, _ -> true }, "always", Duration.ofDays(36_500)).also { it.tick() }
    }
}

/**
 * 주 서버만 마감 캔들을 저장한다. 예비는 최근 [keep] 동안의 마감을 들고 있다가, 이어받을 때 저장한다
 * (옛 주 서버가 죽은 뒤 이어받기 전 사이에 닫힌 칸을 놓치지 않게).
 */
class LeaderGatedSink(
    private val delegate: ClosedCandleSink,
    private val isLeader: () -> Boolean,
    private val clock: Clock,
    private val keep: Duration = Duration.ofSeconds(15),
) : ClosedCandleSink {
    private class Held(val at: Instant, val candle: Candle, val late: Boolean)

    private val held = ArrayDeque<Held>()

    override fun onClosed(c: Candle, late: Boolean) {
        if (isLeader()) return delegate.onClosed(c, late)
        synchronized(held) {
            val now = clock.instant()
            held.addLast(Held(now, c, late))
            trim(now)
        }
    }

    fun flush() {
        val pending = synchronized(held) {
            trim(clock.instant())
            held.toList().also { held.clear() }
        }
        pending.forEach { delegate.onClosed(it.candle, it.late) }
    }

    private fun trim(now: Instant) {
        val oldest = now.minus(keep)
        while (held.firstOrNull()?.at?.isBefore(oldest) == true) held.removeFirst()
    }
}
