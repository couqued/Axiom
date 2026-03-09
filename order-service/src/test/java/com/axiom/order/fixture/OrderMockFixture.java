package com.axiom.order.fixture;

/**
 * order-service 단위 테스트에서 사용하는 mock 주문 데이터 상수.
 * KisOrderApiService.placeMockOrder()의 반환값 형식을 정의한다.
 */
public class OrderMockFixture {

    /** mock 주문 ID 정규식 패턴: "MOCK-" + 8자리 대문자 16진수 */
    public static final String MOCK_ORDER_ID_PATTERN = "^MOCK-[A-F0-9]{8}$";

    /** mock 모드 주문 ID 접두사 */
    public static final String MOCK_ORDER_PREFIX = "MOCK-";

    private OrderMockFixture() {}
}
