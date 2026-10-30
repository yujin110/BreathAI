package com.breathAI.ttobagi_server.domain.faq.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
// FAQ 후보 반영 요청, 운영자가 최종 수정한 질문과 답변 전달
public class FaqApplyRequest {

    @NotNull(message = "분석 ID는 필수입니다.")
    private Long analysisId;

    @NotNull(message = "클러스터 PK(ID)는 필수입니다.")
    private Long clusterId;

    private Integer clusterLabel;

    @NotNull(message = "후보 ID는 필수입니다.") 
    private Long candidateId;

    // 카테고리 코드, 없으면 후보의 값을 사용
    private Integer qType;

   @NotBlank(message = "최종 질문 내용은 필수입니다.")
   private String finalQuestion;

   @NotBlank(message = "최종 답변 내용은 필수입니다.")
   private String finalAnswer;

   @NotEmpty(message = "최종 키워드 목록은 필수입니다.")
   private List<String> keywords;

   // 최종 유사어, 생략하면 후보의 유사어를 쓴다 (확장이면 기존 FAQ의 유사어에 합친다)
   private List<String> synonyms;

   @NotNull(message = "수동 반영 확인 플래그는 필수입니다.")
   private Boolean isManualPatchConfirmed;
   
}