package com.ilia.postupdateanalyzer.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class AppConfiguration {

    @Bean
    fun clock(): Clock {
        return Clock.systemUTC()
    }
}
