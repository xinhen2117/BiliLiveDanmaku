package com.bililive.danmaku.domain.usecase

class ValidateRoomIdUseCase {
    operator fun invoke(input: String): ValidationResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult.Invalid("请输入直播间号")
        }
        if (!trimmed.all { it.isDigit() }) {
            return ValidationResult.Invalid("直播间号只能包含数字")
        }
        if (trimmed.length > 20) {
            return ValidationResult.Invalid("直播间号过长")
        }
        val roomId = trimmed.toLongOrNull() ?: return ValidationResult.Invalid("请输入正确的直播间号")
        if (roomId <= 0) {
            return ValidationResult.Invalid("请输入正确的直播间号")
        }
        return ValidationResult.Valid(roomId)
    }
}

sealed class ValidationResult {
    data class Valid(val roomId: Long) : ValidationResult()
    data class Invalid(val message: String) : ValidationResult()
}
