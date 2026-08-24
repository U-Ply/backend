package com.uply.coupon.it;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회차 리포트가 만족해야 하는 게이트.
 *
 * <p>acceptance.sh 9 단계의 판정 검사를 옮겨온 것이다. 네 회차 테스트가 같은 문장을 복붙하지 않도록 한곳에 모은다.
 */
final class RoundReportAssert {

    private RoundReportAssert() {}

    /** V1~V3. 위반 0 건이고, 규칙이 하나도 빠짐없이 실행됐어야 한다 (test-plan 6.6). */
    static void assertPassed(String markdown, String round) {
        assertRuleSetComplete(markdown, round);

        assertThat(markdown).as("%s 회차 리포트 판정", round).contains("| 판정 | **통과** |");

        assertThat(markdown).as("%s 회차 총 위반", round).contains("| 총 위반 | 0 |");
    }

    /**
     * V0. <b>위반 수를 판정하지 않는다.</b>
     *
     * <p>test-plan 5.4 는 V0 를 정합성 판정 대상에서 빼고 "위반 건수와 차이를 실제 측정값으로 기록" 하라고만 한다. 나아가 "매 실행에서 동일하게
     * 발생한다고 보장할 수 없으므로 반복 실행 결과를 기록한다" 고 못 박는다. 위반이 나오는 것을 게이트로 걸면 문서가 보장하지 않는 것을 통과 조건으로 쓰게 된다.
     *
     * <p>그래서 여기서 보는 것은 두 가지다. 리포트가 <b>생성됐는가</b>, 그리고 V0 를 <b>통과나 실패로 오독하지 않는가</b>. 실제 수치는 §11 비교표의
     * "검증 결과(위반 규칙)" 칸으로 간다.
     */
    static void assertBaselineRecorded(String markdown) {
        assertRuleSetComplete(markdown, "V0");

        assertThat(markdown).as("V0 회차 리포트 판정").contains("| 판정 | **BASELINE**");

        assertThat(markdown)
                .as("V0 는 정합성 판정 대상이 아니다 (test-plan 5.4)")
                .doesNotContain("| 판정 | **통과** |")
                .doesNotContain("| 판정 | **실패**");
    }

    /**
     * 규칙 집합이 온전한지 본다.
     *
     * <p>두 가지를 함께 봐야 한다. 하나는 실행되지 않은 규칙(미실행)이 없는지, 다른 하나는 규칙이 <b>애초에 리포트에 나타났는지</b>다.
     *
     * <p>후자가 중요한 이유가 있다. REC-01 은 검증 Job 이 아니라 대사 Job 이 남긴다. 대사 Job 을 안 돌리면 REC-01 행이 아예 없고, 그러면
     * 미실행 수는 0 이라 "전부 통과" 로 읽힌다. 검사하지 않은 것이 통과로 세어지는 형태가 여기서는 "규칙이 사라지는" 모습으로 나타난다.
     */
    private static void assertRuleSetComplete(String markdown, String round) {
        assertThat(markdown).as("%s 회차에 실행되지 않은 규칙이 있다", round).doesNotContainPattern("미실행 [1-9]");

        assertThat(markdown)
                .as("%s 회차 리포트에 Redis·DB 재고 대사(REC-01)가 없다 — 대사 배치가 돌지 않았다", round)
                .contains("`REC-01`");

        assertThat(markdown)
                .as("%s 회차 리포트에 앱·DB 시계 검사(CLOCK-01)가 없다", round)
                .contains("`CLOCK-01`");
    }
}
