package com.ilia.postupdateanalyzer.support

import com.ilia.postupdateanalyzer.support.fake.FakeAdbEnvironment
import com.ilia.postupdateanalyzer.support.fake.FakeAdbExecutor
import com.ilia.postupdateanalyzer.support.fake.TickingClock
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration(proxyBeanMethods = false)
class FakeAdbTestConfiguration {
    @Bean
    @Primary
    fun fakeAdbExecutor(): FakeAdbExecutor {
        return FakeAdbExecutor()
    }

    @Bean
    @Primary
    fun fakeAdbEnvironment(): FakeAdbEnvironment {
        return FakeAdbEnvironment()
    }

    @Bean
    @Primary
    fun tickingClock(): TickingClock {
        return TickingClock()
    }
}
