package com.opengpt

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class OpengptApplication

fun main(args: Array<String>) {
    runApplication<OpengptApplication>(*args)
}
