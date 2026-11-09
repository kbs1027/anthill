package com.anthill.contract

/** 소스 REST 요청의 우선순위. 앞일수록 먼저 처리한다. */
enum class Priority { USER, RECOVERY, RECONCILE, BACKFILL }
