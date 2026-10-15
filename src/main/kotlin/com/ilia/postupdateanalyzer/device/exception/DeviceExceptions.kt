package com.ilia.postupdateanalyzer.device.exception

class DeviceNotConnectedException(message: String) : RuntimeException(message)

class DeviceBusyException(message: String) : RuntimeException(message)

class NotFoundException(message: String) : RuntimeException(message)
