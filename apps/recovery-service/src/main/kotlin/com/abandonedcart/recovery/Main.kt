package com.abandonedcart.recovery

import com.google.inject.Guice

fun main() {
    val config = AppConfig.fromEnv(System.getenv())
    val injector = Guice.createInjector(AppModule(config))
    val application = injector.getInstance(RecoveryApplication::class.java)
    application.start()
}

