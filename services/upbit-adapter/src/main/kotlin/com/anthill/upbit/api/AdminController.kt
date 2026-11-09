package com.anthill.upbit.api

import com.anthill.upbit.stream.StreamControl
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/** dev 프로필 전용: 업비트 웹소켓을 일부러 끊어 복구를 시험한다. */
@Profile("dev")
@RestController
class AdminController(private val stream: StreamControl) {

    @PostMapping("/admin/stream/pause")
    fun pause(@RequestParam seconds: Long): ResponseEntity<Void> {
        stream.pause(Duration.ofSeconds(seconds))
        return ResponseEntity.accepted().build()
    }
}
