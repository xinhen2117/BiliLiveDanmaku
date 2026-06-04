package com.bililive.danmaku.domain.usecase

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ValidateRoomIdUseCaseTest {

    private lateinit var useCase: ValidateRoomIdUseCase

    @Before
    fun setup() {
        useCase = ValidateRoomIdUseCase()
    }

    @Test
    fun `empty input returns invalid`() {
        val result = useCase("")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `blank input returns invalid`() {
        val result = useCase("   ")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `non-numeric input returns invalid`() {
        val result = useCase("abc123")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `negative number returns invalid`() {
        val result = useCase("-1")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `zero returns invalid`() {
        val result = useCase("0")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `valid room id returns valid`() {
        val result = useCase("21484828")
        assertTrue(result is ValidationResult.Valid)
        assertEquals(21484828L, (result as ValidationResult.Valid).roomId)
    }

    @Test
    fun `very long input returns invalid`() {
        val result = useCase("1".repeat(21))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `large valid room id`() {
        val result = useCase("9999999999")
        assertTrue(result is ValidationResult.Valid)
        assertEquals(9999999999L, (result as ValidationResult.Valid).roomId)
    }

    @Test
    fun `input with spaces is trimmed`() {
        val result = useCase("  12345  ")
        assertTrue(result is ValidationResult.Valid)
        assertEquals(12345L, (result as ValidationResult.Valid).roomId)
    }
}
