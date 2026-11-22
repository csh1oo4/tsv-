package com.tsv.dutycalendar;

/**
 * View ids of the generated widget_* layouts (42-cell month grid, team grid).
 * Kept apart so the providers can loop over the static cells.
 */
final class WidgetIds {

    private WidgetIds() {
    }

    /** Widget4DayProvider: 날짜·요일·배지 4칸. */
    static final int[] DAY4_DATE = {R.id.w_4d_date0, R.id.w_4d_date1, R.id.w_4d_date2, R.id.w_4d_date3};
    static final int[] DAY4_DOW = {R.id.w_4d_dow0, R.id.w_4d_dow1, R.id.w_4d_dow2, R.id.w_4d_dow3};
    static final int[] DAY4_BADGE = {R.id.w_4d_badge0, R.id.w_4d_badge1, R.id.w_4d_badge2, R.id.w_4d_badge3};

    /** WidgetTeam4DayProvider: 조 4개 × 4일 배지. */
    static final int[] TEAM0_BADGE = {R.id.w_t_b0_0, R.id.w_t_b0_1, R.id.w_t_b0_2, R.id.w_t_b0_3};
    static final int[] TEAM1_BADGE = {R.id.w_t_b1_0, R.id.w_t_b1_1, R.id.w_t_b1_2, R.id.w_t_b1_3};
    static final int[] TEAM2_BADGE = {R.id.w_t_b2_0, R.id.w_t_b2_1, R.id.w_t_b2_2, R.id.w_t_b2_3};
    static final int[] TEAM3_BADGE = {R.id.w_t_b3_0, R.id.w_t_b3_1, R.id.w_t_b3_2, R.id.w_t_b3_3};
    static final int[][] TEAM_BADGE = {TEAM0_BADGE, TEAM1_BADGE, TEAM2_BADGE, TEAM3_BADGE};
    static final int[] TEAM_NAME = {R.id.w_t_name0, R.id.w_t_name1, R.id.w_t_name2, R.id.w_t_name3};
    static final int[] TEAM_ROW = {R.id.w_t_row0, R.id.w_t_row1, R.id.w_t_row2, R.id.w_t_row3};
    static final int[] TEAM_HEADER_DATE = {R.id.w_t_h_date0, R.id.w_t_h_date1, R.id.w_t_h_date2, R.id.w_t_h_date3};
    static final int[] TEAM_HEADER_DOW = {R.id.w_t_h_dow0, R.id.w_t_h_dow1, R.id.w_t_h_dow2, R.id.w_t_h_dow3};

    /** WidgetMonthProvider: 6주 × 7일 = 42칸. */    static final int[] MONTH_DATE = {R.id.w_m_c0_date, R.id.w_m_c1_date, R.id.w_m_c2_date, R.id.w_m_c3_date, R.id.w_m_c4_date, R.id.w_m_c5_date, R.id.w_m_c6_date, R.id.w_m_c7_date, R.id.w_m_c8_date, R.id.w_m_c9_date, R.id.w_m_c10_date, R.id.w_m_c11_date, R.id.w_m_c12_date, R.id.w_m_c13_date, R.id.w_m_c14_date, R.id.w_m_c15_date, R.id.w_m_c16_date, R.id.w_m_c17_date, R.id.w_m_c18_date, R.id.w_m_c19_date, R.id.w_m_c20_date, R.id.w_m_c21_date, R.id.w_m_c22_date, R.id.w_m_c23_date, R.id.w_m_c24_date, R.id.w_m_c25_date, R.id.w_m_c26_date, R.id.w_m_c27_date, R.id.w_m_c28_date, R.id.w_m_c29_date, R.id.w_m_c30_date, R.id.w_m_c31_date, R.id.w_m_c32_date, R.id.w_m_c33_date, R.id.w_m_c34_date, R.id.w_m_c35_date, R.id.w_m_c36_date, R.id.w_m_c37_date, R.id.w_m_c38_date, R.id.w_m_c39_date, R.id.w_m_c40_date, R.id.w_m_c41_date};
    static final int[] MONTH_SHIFT = {R.id.w_m_c0_shift, R.id.w_m_c1_shift, R.id.w_m_c2_shift, R.id.w_m_c3_shift, R.id.w_m_c4_shift, R.id.w_m_c5_shift, R.id.w_m_c6_shift, R.id.w_m_c7_shift, R.id.w_m_c8_shift, R.id.w_m_c9_shift, R.id.w_m_c10_shift, R.id.w_m_c11_shift, R.id.w_m_c12_shift, R.id.w_m_c13_shift, R.id.w_m_c14_shift, R.id.w_m_c15_shift, R.id.w_m_c16_shift, R.id.w_m_c17_shift, R.id.w_m_c18_shift, R.id.w_m_c19_shift, R.id.w_m_c20_shift, R.id.w_m_c21_shift, R.id.w_m_c22_shift, R.id.w_m_c23_shift, R.id.w_m_c24_shift, R.id.w_m_c25_shift, R.id.w_m_c26_shift, R.id.w_m_c27_shift, R.id.w_m_c28_shift, R.id.w_m_c29_shift, R.id.w_m_c30_shift, R.id.w_m_c31_shift, R.id.w_m_c32_shift, R.id.w_m_c33_shift, R.id.w_m_c34_shift, R.id.w_m_c35_shift, R.id.w_m_c36_shift, R.id.w_m_c37_shift, R.id.w_m_c38_shift, R.id.w_m_c39_shift, R.id.w_m_c40_shift, R.id.w_m_c41_shift};
    static final int[] MONTH_ROW = {R.id.w_m_row0, R.id.w_m_row1, R.id.w_m_row2, R.id.w_m_row3, R.id.w_m_row4, R.id.w_m_row5};

    /** 한달 위젯 칸 안의 일정 줄(최대 2줄): ev1 = 첫 일정/공휴일, ev2 = 두 번째 일정 또는 +N건. */
    static final int[] MONTH_EV1 = {R.id.w_m_c0_ev1, R.id.w_m_c1_ev1, R.id.w_m_c2_ev1, R.id.w_m_c3_ev1, R.id.w_m_c4_ev1, R.id.w_m_c5_ev1, R.id.w_m_c6_ev1, R.id.w_m_c7_ev1, R.id.w_m_c8_ev1, R.id.w_m_c9_ev1, R.id.w_m_c10_ev1, R.id.w_m_c11_ev1, R.id.w_m_c12_ev1, R.id.w_m_c13_ev1, R.id.w_m_c14_ev1, R.id.w_m_c15_ev1, R.id.w_m_c16_ev1, R.id.w_m_c17_ev1, R.id.w_m_c18_ev1, R.id.w_m_c19_ev1, R.id.w_m_c20_ev1, R.id.w_m_c21_ev1, R.id.w_m_c22_ev1, R.id.w_m_c23_ev1, R.id.w_m_c24_ev1, R.id.w_m_c25_ev1, R.id.w_m_c26_ev1, R.id.w_m_c27_ev1, R.id.w_m_c28_ev1, R.id.w_m_c29_ev1, R.id.w_m_c30_ev1, R.id.w_m_c31_ev1, R.id.w_m_c32_ev1, R.id.w_m_c33_ev1, R.id.w_m_c34_ev1, R.id.w_m_c35_ev1, R.id.w_m_c36_ev1, R.id.w_m_c37_ev1, R.id.w_m_c38_ev1, R.id.w_m_c39_ev1, R.id.w_m_c40_ev1, R.id.w_m_c41_ev1};
    static final int[] MONTH_EV2 = {R.id.w_m_c0_ev2, R.id.w_m_c1_ev2, R.id.w_m_c2_ev2, R.id.w_m_c3_ev2, R.id.w_m_c4_ev2, R.id.w_m_c5_ev2, R.id.w_m_c6_ev2, R.id.w_m_c7_ev2, R.id.w_m_c8_ev2, R.id.w_m_c9_ev2, R.id.w_m_c10_ev2, R.id.w_m_c11_ev2, R.id.w_m_c12_ev2, R.id.w_m_c13_ev2, R.id.w_m_c14_ev2, R.id.w_m_c15_ev2, R.id.w_m_c16_ev2, R.id.w_m_c17_ev2, R.id.w_m_c18_ev2, R.id.w_m_c19_ev2, R.id.w_m_c20_ev2, R.id.w_m_c21_ev2, R.id.w_m_c22_ev2, R.id.w_m_c23_ev2, R.id.w_m_c24_ev2, R.id.w_m_c25_ev2, R.id.w_m_c26_ev2, R.id.w_m_c27_ev2, R.id.w_m_c28_ev2, R.id.w_m_c29_ev2, R.id.w_m_c30_ev2, R.id.w_m_c31_ev2, R.id.w_m_c32_ev2, R.id.w_m_c33_ev2, R.id.w_m_c34_ev2, R.id.w_m_c35_ev2, R.id.w_m_c36_ev2, R.id.w_m_c37_ev2, R.id.w_m_c38_ev2, R.id.w_m_c39_ev2, R.id.w_m_c40_ev2, R.id.w_m_c41_ev2};
}
