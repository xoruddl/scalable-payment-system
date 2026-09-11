package com.remittance.account.saga;

/**
 * 단계가 업무적으로 실패했을 때 대신 남길 이벤트.
 *
 * 전진 단계만 갖는다. 보상 단계는 물러날 곳이 없어서 — 보상의 보상은 없으므로 —
 * 이것 대신 예외를 그대로 밖으로 내보내 재배달에 맡긴다.
 */
public record Fallback(String eventType, Object body) {
}
