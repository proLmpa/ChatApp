package com.chat.share

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito

@Suppress("UNCHECKED_CAST")
class MockitoTest {

    @Test
    @DisplayName("Basic test")
    fun test() {
        // 1. Mock 생성
        var mockList: List<String> = Mockito.mock(List::class.java) as List<String>

        /*
         * 2. Stub : 호출 예상 동작 설젇
         * 기본 객체 : 가짜 (Mock)
         * 기본 동작 : 모든 메서드가 기본값 반환
         * 용도 : 의존성을 완전히 대체하여 입력 통제
         * 생성 함수 : mock(T::class.java) 또는 @Mock
         */
        Mockito.`when`(mockList.size).thenReturn(5)
        println("Result : ${mockList.size}")

        // 3. Verify : 모의 객체의 메서드 호출 검증
        Mockito.verify(mockList).size

        /*
         * 4. Spy : 객체의 메서드 동작 지정
         * 기본 객체 : 실제 (Real Instance)
         * 기본 동작 : Stubbing이 없으면 실제 코드 실행
         * 용도 : 객체의 일부 기능만 격리하거나 감시
         * 생성 함수 : spy(instance) 또는 @Spy
         */
        Mockito.doReturn("Mocked").`when`(mockList)[0]
        println("First Element: ${mockList.get(0)}")
    }
}