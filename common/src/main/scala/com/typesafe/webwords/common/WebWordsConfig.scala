package com.typesafe.webwords.common

/**
 * This class represents our app configuration.
 */
case class WebWordsConfig(mongoURL: Option[String], port: Option[Int])

object WebWordsConfig {
    def apply(): WebWordsConfig = {
        val mongoURL = Option(System.getenv("MONGOHQ_URL"))
        val port = Option(System.getenv("PORT")) map { s => Integer.parseInt(s) }
        val config = WebWordsConfig(mongoURL, port)
        println("Configuration is: " + config)
        config
    }
}
