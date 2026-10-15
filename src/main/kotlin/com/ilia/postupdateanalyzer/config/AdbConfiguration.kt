package com.ilia.postupdateanalyzer.config

import com.ilia.postupdateanalyzer.adb.client.AdbClient
import com.ilia.postupdateanalyzer.adb.environment.AdbEnvironment
import com.ilia.postupdateanalyzer.adb.executor.AdbExecutor
import com.ilia.postupdateanalyzer.adb.executor.ProcessAdbExecutor
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppProperties::class)
class AdbConfiguration {

    @Bean
    fun adbExecutor(environment: AdbEnvironment): AdbExecutor {
        return ProcessAdbExecutor { environment.command() }
    }

    @Bean
    fun adbClient(executor: AdbExecutor, properties: AppProperties): AdbClient {
        return AdbClient(executor, properties.commandTimeout, properties.transferTimeout)
    }
}
