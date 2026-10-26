package com.breathAI.ttobagi_server.domain.dashboard.service;

import com.breathAI.ttobagi_server.domain.auth.entity.User;
import com.breathAI.ttobagi_server.domain.auth.repository.UserRepository;
import com.breathAI.ttobagi_server.domain.dashboard.dto.*;
import com.breathAI.ttobagi_server.domain.dashboard.entity.*;
import com.breathAI.ttobagi_server.domain.dashboard.repository.*;
import com.breathAI.ttobagi_server.domain.faq.entity.*;
import com.breathAI.ttobagi_server.domain.faq.repository.*;
import com.breathAI.ttobagi_server.global.enums.UnansweredReason;
import com.breathAI.ttobagi_server.global.exception.CustomException;
import com.breathAI.ttobagi_server.global.exception.ErrorCode;
import com.breathAI.ttobagi_server.global.util.WebClientUtil;
import com.breathAI.ttobagi_server.global.util.SseEmitterManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.multipart.MultipartFile;
import org.apache.commons.codec.digest.DigestUtils;
import java.io.IOException;
import java.util.Optional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 파일 업로드, AI 분석 실행 및 콜백 수신, 대시보드 지표 조회
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    // AI 서버(/pipeline)가 받는 군집화 설정의 최솟값
    private static final int MIN_CLUSTER_SIZE_LIMIT = 2;
    private static final int MIN_SAMPLES_LIMIT = 1;

    private final UploadFileRepository uploadFileRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final UsageStatRepository usageStatRepository;
    private final UserRepository userRepository;
    private final WebClientUtil webClientUtil;
    private final AnalysisUploadReader analysisUploadReader;
    private final AnalysisUploadValidator analysisUploadValidator;
    private final ClusterRepository clusterRepository;
    private final ClusterLogMapRepository clusterLogMapRepository;
    private final FaqCandidateRepository faqCandidateRepository;
    private final SynonymCandidateRepository synonymCandidateRepository;
    private final FaqCandidateMatchRepository faqCandidateMatchRepository;
    private final UnansweredStatRepository unansweredStatRepository;
    private final SurgeDetectionRepository surgeDetectionRepository;
    private final SurgeStatRepository surgeStatRepository;
    private final PerformanceComparisonRepository performanceComparisonRepository;
    private final StatDateRepository statDateRepository;
    private final AnalysisDailyStatRepository analysisDailyStatRepository;
    private final ObjectMapper objectMapper;
    private final SseEmitterManager sseEmitterManager;
    private final UploadArchiveService uploadArchiveService;
    private final AnalysisHistoryRepository analysisHistoryRepository;

    // 월 단위 챗봇 사용량 조회, 전월 대비 증가율 포함
    @Transactional(readOnly = true)
    public UsageResponse getUsage(int year, int month) {
        List<UsageStat> currentMonthStats = usageStatRepository.findByYearAndMonth(year, month);

        long totalUsageCount = currentMonthStats.stream()
                .mapToLong(UsageStat::getTotalCnt)
                .sum();

        LocalDate lastMonthDate = LocalDate.of(year, month, 1).minusMonths(1);
        List<UsageStat> lastMonthStats = usageStatRepository.findByYearAndMonth(
                lastMonthDate.getYear(), lastMonthDate.getMonthValue());

        long lastMonthTotal = lastMonthStats.stream()
                .mapToLong(UsageStat::getTotalCnt)
                .sum();

        double increaseRate = 0.0;
        if (lastMonthTotal > 0) {
            increaseRate = ((double) (totalUsageCount - lastMonthTotal) / lastMonthTotal) * 100;
        }

        List<UsageResponse.DailyUsage> dailyUsage = currentMonthStats.stream()
                .map(s -> UsageResponse.DailyUsage.builder()
                        .date(s.getStatDate().getStatDate())
                        .count(s.getTotalCnt())
                        .build())
                .collect(Collectors.toList());

        return UsageResponse.builder()
                .totalUsageCount(totalUsageCount)
                .increaseRate(Math.round(increaseRate * 100) / 100.0)
                .dailyUsage(dailyUsage)
                .build();
    }

    // 기간 내 완료된 분석을 합산해 조회, 기간 미지정 시 최신 분석 1건 사용
    // mine은 내 이력에 남은 분석만, all은 이력 삭제와 관계없이 완료된 분석 전체를 쓴다
    @Transactional(readOnly = true)
    public AnalyzeResultResponse getAnalyzeResult(LocalDate startDate, LocalDate endDate,
                                                  HistoryScope scope, String email) {
        User user = scope == HistoryScope.MINE ? findUser(email) : null;
        List<AnalysisJob> jobs;

        if (startDate != null && endDate != null) {
                jobs = user != null
                        ? analysisJobRepository.findRequestedByStatusAndPeriod(
                                user, AnalysisJob.Status.COMPLETED, startDate, endDate)
                        : analysisJobRepository.findByStatusAndPeriod(
                                AnalysisJob.Status.COMPLETED, startDate, endDate);
                if (jobs.isEmpty()) throw new CustomException(ErrorCode.ANALYSIS_NOT_FOUND);
        } else {
                Optional<AnalysisJob> found = user != null
                        ? analysisJobRepository.findRequestedByStatus(
                                user, AnalysisJob.Status.COMPLETED, PageRequest.of(0, 1)).stream().findFirst()
                        : analysisJobRepository.findFirstByStatusOrderByFinishedAtDesc(AnalysisJob.Status.COMPLETED);
                AnalysisJob latest = found
                        .orElseThrow(() -> new CustomException(ErrorCode.ANALYSIS_NOT_FOUND));
                jobs = List.of(latest);
                startDate = latest.getPeriodStartDate();
                endDate = latest.getPeriodEndDate();
        }

        return buildAnalyzeResultResponse(jobs, startDate, endDate);
    }

    // 분석 ID 단위 결과 조회
    @Transactional(readOnly = true)
    public FileAnalyzeResultResponse getFileAnalyzeResult(Long analysisId) {
        AnalysisJob job = analysisJobRepository.findById(analysisId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANALYSIS_NOT_FOUND));

        AnalyzeResultResponse commonData = buildAnalyzeResultResponse(
                List.of(job), job.getPeriodStartDate(), job.getPeriodEndDate());

        return FileAnalyzeResultResponse.builder()
                .analysisId(job.getAnalysisId())
                .fileName(job.getFileName())
                .status(job.getStatus().name())
                .periodStartDate(job.getPeriodStartDate() != null ? job.getPeriodStartDate().toString() : null)
                .periodEndDate(job.getPeriodEndDate() != null ? job.getPeriodEndDate().toString() : null)
                .systemStatus(commonData.getSystemStatus())
                .performanceMetrics(commonData.getPerformanceMetrics())
                .trend(commonData.getTrend())
                .usageStats(commonData.getUsageStats())
                .burstKeywords(commonData.getBurstKeywords())
                .unanswerAnalysis(commonData.getUnanswerAnalysis())
                .clusteringView(commonData.getClusteringView())
                .faqCandidates(commonData.getFaqCandidates())
                .evaluation(commonData.getEvaluation())
                .surgeDetection(commonData.getSurgeDetection())
                .build();
    }

    // 분석 결과 응답 조립, 조회 API 두 곳에서 공용으로 사용
    private AnalyzeResultResponse buildAnalyzeResultResponse(
        List<AnalysisJob> jobs, LocalDate startDate, LocalDate endDate) {

        // 1. 시스템 상태 - AI가 보낸 파일별 건수를 합산한다
        // 요약값이 없는 예전 분석만 저장된 좌표 수로 센다 (좌표는 군집화된 로그만 있어 전체 건수보다 적을 수 있다)
        List<ClusterLogMap> allLogs = new ArrayList<>();
        int totalLogCount = 0;
        int correctCount = 0;
        int lowQualityCount = 0;
        int unansweredCount = 0;
        for (AnalysisJob job : jobs) {
            List<ClusterLogMap> logs = clusterLogMapRepository.findAllByCluster_AnalysisJob(job);
            allLogs.addAll(logs);
            if (job.hasSummary()) {
                totalLogCount += job.getTotalLogCount();
                correctCount += zeroIfNull(job.getCorrectCount());
                lowQualityCount += zeroIfNull(job.getLowQualityCount());
                unansweredCount += zeroIfNull(job.getUnanswerCount());
            } else {
                totalLogCount += logs.size();
                correctCount += countLogs(logs, ClusterLogMap.LogType.CORRECT);
                lowQualityCount += countLogs(logs, ClusterLogMap.LogType.LOW_QUALITY);
                unansweredCount += countLogs(logs, ClusterLogMap.LogType.UNANSWER);
            }
        }

        AnalyzeResultResponse.SystemStatus systemStatus = AnalyzeResultResponse.SystemStatus.builder()
                .totalLogCount(totalLogCount)
                .samplingStatus(AnalyzeResultResponse.SystemStatus.SamplingStatus.builder()
                        .correct(correctCount)
                        .lowQuality(lowQualityCount)
                        .unanswer(unansweredCount)
                        .build())
                .build();

        // 2. 성능 지표 - 마지막 job 기준
        AnalysisJob latestJob = jobs.get(jobs.size() - 1);
        Long latestAnalysisId = latestJob.getAnalysisId();

        PerformanceComparison comparison = performanceComparisonRepository
                .findByAnalysisJob_AnalysisId(latestAnalysisId).orElse(null);

        AnalyzeResultResponse.PerformanceMetrics performanceMetrics = null;
        if (jobs.stream().anyMatch(AnalysisJob::hasSummary)) {
                // 개선 전 미답변율: 파일 하나면 AI 값을 그대로 쓰고, 여러 파일은 합친 건수로 같은 공식(미답변 ÷ 전체 × 100)을 적용한다
                double currentUnanswerRate = jobs.size() == 1 && latestJob.getCurrentUnanswerRate() != null
                        ? latestJob.getCurrentUnanswerRate().doubleValue()
                        : (totalLogCount > 0 ? Math.round(unansweredCount * 10000.0 / totalLogCount) / 100.0 : 0.0);
                performanceMetrics = AnalyzeResultResponse.PerformanceMetrics.builder()
                        .currentUnanswerRate(currentUnanswerRate)
                        .predictedAccuracyGain(latestJob.getPredictedAccuracyGain() != null
                                ? latestJob.getPredictedAccuracyGain().doubleValue()
                                : comparison != null && comparison.getAccuracyGain() != null
                                        ? comparison.getAccuracyGain().doubleValue() : 0.0)
                        .resolvedCountByAI(latestJob.getResolvedCountByAi() != null
                                ? latestJob.getResolvedCountByAi()
                                : comparison != null && comparison.getResolvedCountByAi() != null
                                        ? comparison.getResolvedCountByAi() : 0)
                        .build();
        } else if (comparison != null) {
                performanceMetrics = AnalyzeResultResponse.PerformanceMetrics.builder()
                        .currentUnanswerRate(comparison.getAfterUnanswerRate() != null
                                ? comparison.getAfterUnanswerRate().doubleValue() : 0.0)
                        .predictedAccuracyGain(comparison.getAccuracyGain() != null
                                ? comparison.getAccuracyGain().doubleValue() : 0.0)
                        .resolvedCountByAI(comparison.getResolvedCountByAi() != null
                                ? comparison.getResolvedCountByAi() : 0)
                        .build();
        }

        // 3. 트렌드·날짜별 통계 - 파일별 날짜 통계를 날짜마다 합산한다
        List<AnalysisDailyStat> dailyStats = analysisDailyStatRepository.findByAnalysisJob_AnalysisIdInOrderByStatDateAsc(
                jobs.stream().map(AnalysisJob::getAnalysisId).toList());
        List<AnalyzeResultResponse.UsageStatItem> usageStatItems = sumDailyStats(dailyStats);
        List<AnalyzeResultResponse.TrendItem> trend;
        if (!dailyStats.isEmpty()) {
                trend = usageStatItems.stream()
                        .map(s -> AnalyzeResultResponse.TrendItem.builder()
                                .date(s.getStatDate())
                                .unanswerCount(s.getUnanswerCnt())
                                .lowQualityCount(s.getLowQualityCount())
                                .build())
                        .collect(Collectors.toList());
        } else {
                // 파일별 통계가 생기기 전 분석은 예전처럼 공용 사용량 테이블에서 읽는다
                List<UsageStat> usageStats = (startDate != null && endDate != null)
                        ? usageStatRepository.findByStatDateBetween(startDate, endDate)
                        : List.of();
                trend = usageStats.stream()
                        .map(s -> AnalyzeResultResponse.TrendItem.builder()
                                .date(s.getStatDate().getStatDate().toString())
                                .unanswerCount(s.getUnanswerCnt())
                                .lowQualityCount(s.getLowQualityCount())
                                .build())
                        .collect(Collectors.toList());
        }

        // 4. 급증 키워드 - 모든 job 합산
        List<SurgeDetection> surgeDetections = jobs.stream()
                .flatMap(job -> surgeDetectionRepository
                        .findByAnalysisJobAnalysisId(job.getAnalysisId()).stream())
                .collect(Collectors.toList());

        List<AnalyzeResultResponse.BurstKeyword> burstKeywords = surgeDetections.stream()
                .map(s -> AnalyzeResultResponse.BurstKeyword.builder()
                        .keyword(s.getKeyword())
                        .increasedRate(s.getIncreasedRate() != null
                                ? s.getIncreasedRate().doubleValue() : 0.0)
                        .count(s.getKeywordCount())
                        .build())
                .collect(Collectors.toList());

        // 5. 미답변 사유별 분석 - 모든 job 합산
        List<UnansweredStat> unansweredStats = jobs.stream()
                .flatMap(job -> unansweredStatRepository
                        .findByAnalysisJob_AnalysisId(job.getAnalysisId()).stream())
                .collect(Collectors.toList());

        List<AnalyzeResultResponse.UnansweredAnalysis.ReasonItem> reasonItems = unansweredStats.stream()
                .map(u -> AnalyzeResultResponse.UnansweredAnalysis.ReasonItem.builder()
                        .reason(u.getReason().getDescription())
                        .count(u.getUnanswerCount())
                        .build())
                .collect(Collectors.toList());

        AnalyzeResultResponse.UnansweredAnalysis unansweredAnalysis =
                AnalyzeResultResponse.UnansweredAnalysis.builder()
                        .byReason(reasonItems)
                        .build();

        // 6. 클러스터링 뷰 - 모든 job 합산
        List<Cluster> clusters = jobs.stream()
                .flatMap(job -> clusterRepository
                        .findByAnalysisJobAnalysisId(job.getAnalysisId()).stream())
                .collect(Collectors.toList());

        List<AnalyzeResultResponse.ClusteringView.Point> points = allLogs.stream()
                .map(this::buildPoint)
                .collect(Collectors.toList());

        List<AnalyzeResultResponse.ClusteringView.ClusterName> clusterNames = clusters.stream()
                .map(this::buildClusterName)
                .collect(Collectors.toList());

        AnalyzeResultResponse.ClusteringView clusteringView =
                AnalyzeResultResponse.ClusteringView.builder()
                        .points(points)
                        .clusterNames(clusterNames)
                        .build();

        // 7. FAQ 후보 - 모든 job 합산
        List<FaqCandidate> candidates = jobs.stream()
                .flatMap(job -> faqCandidateRepository
                        .findByAnalysisJobAnalysisId(job.getAnalysisId()).stream())
                .collect(Collectors.toList());

        List<AnalyzeResultResponse.FaqCandidate> faqCandidates = candidates.stream()
                .map(this::buildFaqCandidateItem)
                .collect(Collectors.toList());

        // 8. 평가 지표 - 마지막 job 기준
        AnalyzeResultResponse.Evaluation evaluation = null;
        if (comparison != null) {
                evaluation = AnalyzeResultResponse.Evaluation.builder()
                        .beforeStatDate(comparison.getUsageStat() != null
                                ? comparison.getUsageStat().getStatDate().getStatDate().toString() : null)
                        .afterUnanswerCount(comparison.getAfterUnanswerCnt() != null
                                ? comparison.getAfterUnanswerCnt() : 0)
                        .afterUnanswerRate(comparison.getAfterUnanswerRate() != null
                                ? comparison.getAfterUnanswerRate().doubleValue() : 0.0)
                        .accuracyGain(comparison.getAccuracyGain() != null
                                ? comparison.getAccuracyGain().doubleValue() : 0.0)
                        .resolvedCountByAI(comparison.getResolvedCountByAi() != null
                                ? comparison.getResolvedCountByAi() : 0)
                        .falsePositiveRate(comparison.getFalsePositiveRate() != null
                                ? comparison.getFalsePositiveRate().doubleValue() : 0.0)
                        .evalThreshold(comparison.getEvalThreshold() != null
                                ? comparison.getEvalThreshold().doubleValue() : 0.0)
                        .build();
        }

        // 9. 급증 탐지 상세
        List<AnalyzeResultResponse.SurgeDetection> surgeDetectionItems = surgeDetections.stream()
                .map(s -> {
                        List<SurgeStat> surgeStats = surgeStatRepository
                                .findBySurgeDetectionSurgeId(s.getSurgeId());

                        List<AnalyzeResultResponse.SurgeDetection.PeriodStat> periodStats = surgeStats.stream()
                                .map(ss -> AnalyzeResultResponse.SurgeDetection.PeriodStat.builder()
                                        .statDate(ss.getStatDate().getStatDate().toString())
                                        .periodType(ss.getPeriodType().name())
                                        .count(ss.getCount())
                                        .build())
                                .collect(Collectors.toList());

                        return AnalyzeResultResponse.SurgeDetection.builder()
                                .keyword(s.getKeyword())
                                .isNew(s.getIsNew())
                                .relatedClusterLabel(s.getRelatedCluster() != null
                                        ? s.getRelatedCluster().getClusterLabel() : -1)
                                .periodStats(periodStats)
                                .build();
                })
                .collect(Collectors.toList());

        return AnalyzeResultResponse.builder()
                .periodStartDate(startDate != null ? startDate.toString() : null)
                .periodEndDate(endDate != null ? endDate.toString() : null)
                .systemStatus(systemStatus)
                .performanceMetrics(performanceMetrics)
                .trend(trend)
                .usageStats(usageStatItems)
                .burstKeywords(burstKeywords)
                .unanswerAnalysis(unansweredAnalysis)
                .clusteringView(clusteringView)
                .faqCandidates(faqCandidates)
                .evaluation(evaluation)
                .surgeDetection(surgeDetectionItems)
                .build();
        }

    // 파일 업로드 후 AI 분석 파이프라인 시작
    // 같은 파일을 같은 옵션으로 돌린 분석이 있으면 기존 결과를 반환하고, 없거나 실패했으면 같은 업로드 기록으로 새로 분석한다
    // 어느 경우든 요청한 사람의 이력에 남긴다
    @Transactional
    public NewAnalyzeResponse uploadAndStartAnalysis(
            MultipartFile file,
            String email,
            String periodStartDate,
            String periodEndDate,
            boolean isMaskingEnabled,
            boolean isTranslationEnabled,
            Integer minClusterSize,
            Integer minSamples) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // AI 서버와 같은 범위로 미리 검사해, 분석이 시작된 뒤 거절되어 실패로 끝나지 않게 한다
        if ((minClusterSize != null && minClusterSize < MIN_CLUSTER_SIZE_LIMIT)
                || (minSamples != null && minSamples < MIN_SAMPLES_LIMIT)) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        }

        var pipelineFile = analysisUploadReader.read(file);
        // AI가 거절할 내용을 미리 찾아 행별로 알려 준다 (분석 기록을 만들기 전)
        analysisUploadValidator.validate(pipelineFile);

        // 요청이 끝나면 업로드 임시 파일이 지워질 수 있어, AI 전송용 내용을 여기서 미리 읽는다
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new CustomException(ErrorCode.FILE_READ_ERROR);
        }
        String fileHash = DigestUtils.sha256Hex(fileBytes);

        LocalDate parsedStartDate = periodStartDate != null ? LocalDate.parse(periodStartDate) : null;
        LocalDate parsedEndDate = periodEndDate != null ? LocalDate.parse(periodEndDate) : null;
        String originalFileName = file.getOriginalFilename();

        UploadFile uploadFile;
        Optional<UploadFile> existingUpload = uploadFileRepository.findByFileHash(fileHash);
        if (existingUpload.isPresent()) {
                // 옵션이 같은 분석 중 가장 최근 것이 실패가 아니면 재사용한다
                Optional<AnalysisJob> existingJob = analysisJobRepository
                        .findByUploadFileOrderByCreatedAtDesc(existingUpload.get()).stream()
                        .filter(job -> job.hasSameOptions(parsedStartDate, parsedEndDate,
                                isMaskingEnabled, isTranslationEnabled, minClusterSize, minSamples))
                        .findFirst();
                if (existingJob.isPresent() && existingJob.get().getStatus() != AnalysisJob.Status.FAIL) {
                AnalysisJob job = existingJob.get();
                recordHistory(user, job);
                log.info("중복 파일 감지 - 기존 분석 결과 반환: analysisId={}", job.getAnalysisId());
                return NewAnalyzeResponse.builder()
                        .analysisId(job.getAnalysisId())
                        .uploadId(existingUpload.get().getUploadId())
                        .fileName(job.getFileName())
                        .status(job.getStatus().name())
                        .message("동일한 파일의 분석 결과가 이미 존재합니다.")
                        .submittedAt(job.getCreatedAt())
                        .build();
                }
                // 해시로 업로드 기록을 하나만 찾으므로, 새로 만들지 않고 기존 기록을 다시 쓴다
                uploadFile = existingUpload.get();
                log.info("같은 옵션의 분석이 없거나 실패한 파일 재분석: uploadId={}", uploadFile.getUploadId());
        } else {
            String newUploadId = UUID.randomUUID().toString();
            uploadFile = UploadFile.builder()
                    .uploadId(newUploadId)
                    .uploadedBy(user)
                    .originalFileName(originalFileName)
                    .fileName(newUploadId + "_" + originalFileName)
                    .filePath("")
                    .fileSize(file.getSize())
                    .fileHash(fileHash)
                    .build();
            uploadFileRepository.save(uploadFile);
        }
        String uploadId = uploadFile.getUploadId();

        AnalysisJob analysisJob = AnalysisJob.builder()
                .uploadFile(uploadFile)
                .fileName(originalFileName)
                .isMaskingEnabled(isMaskingEnabled)
                .isTranslationEnabled(isTranslationEnabled)
                .periodStartDate(parsedStartDate)
                .periodEndDate(parsedEndDate)
                .minClusterSize(minClusterSize)
                .minSamples(minSamples)
                .build();
        analysisJobRepository.save(analysisJob);
        recordHistory(user, analysisJob);
        Long analysisId = analysisJob.getAnalysisId();
        // 실패 파일 재분석처럼 같은 업로드 기록을 다시 쓰는 경우 원본은 이미 보관돼 있다
        boolean alreadyArchived = uploadFile.getFilePath() != null && !uploadFile.getFilePath().isBlank();

        // 분석 기록이 커밋된 뒤에 요청해야 실패 처리와 AI 콜백이 기록을 찾을 수 있다
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                webClientUtil.sendPipeline(
                        uploadId,
                        analysisId,
                        pipelineFile.content(),
                        pipelineFile.filename(),
                        isMaskingEnabled,
                        isTranslationEnabled,
                        periodStartDate != null ? periodStartDate : "",
                        periodEndDate != null ? periodEndDate : "",
                        minClusterSize,
                        minSamples,
                        reason -> markPipelineRequestFailed(analysisId, reason));
                if (!alreadyArchived) {
                    // 변환 전 원본을 그대로 보관한다 (xlsx는 xlsx로)
                    uploadArchiveService.archive(uploadId, fileBytes, originalFileName);
                }
            }
        });

        log.info("파일 업로드 및 분석 시작: analysisId={}, uploadId={}", analysisId, uploadId);

        return NewAnalyzeResponse.builder()
                .analysisId(analysisId)
                .uploadId(uploadId)
                .fileName(originalFileName)
                .status(analysisJob.getStatus().name())
                .message("파일 업로드 및 AI 분석 파이프라인을 시작합니다.")
                .submittedAt(analysisJob.getCreatedAt())
                .build();
    }

    // AI 서버에 분석 요청을 보내지 못했을 때 대기 중인 분석을 실패로 바꾸고 알린다
    // 요청 스레드 밖에서 실행되므로 저장소 메서드 단위 트랜잭션으로 처리한다
    private void markPipelineRequestFailed(Long analysisId, String reason) {
        analysisJobRepository.findById(analysisId).ifPresent(job -> {
            // 그 사이 AI 콜백으로 진행된 분석은 건드리지 않는다
            if (job.getStatus() != AnalysisJob.Status.PENDING) return;
            job.fail(reason);
            analysisJobRepository.save(job);
            sseEmitterManager.send(analysisId, "FAIL", "분석에 실패했습니다.");
        });
    }

    // 분석 진행 상태 조회, SSE 끊김 시 폴링 용도
    @Transactional(readOnly = true)
    public AnalyzeStatusResponse getAnalysisStatus(Long analysisId) {
        AnalysisJob analysisJob = analysisJobRepository.findById(analysisId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANALYSIS_NOT_FOUND));

        return AnalyzeStatusResponse.builder()
                .uploadId(analysisJob.getUploadFile().getUploadId())
                .analysisId(analysisJob.getAnalysisId())
                .status(analysisJob.getStatus().name())
                .message(getStatusMessage(analysisJob.getStatus()))
                .updatedAt(analysisJob.getStartedAt() != null
                        ? analysisJob.getStartedAt()
                        : analysisJob.getCreatedAt())
                .build();
    }

    // 분석 진행 상태 실시간 구독(SSE)
    // 연결 즉시 현재 상태를 보내 클라이언트가 연결 여부와 진행 상황을 바로 알 수 있게 한다
    // 이미 끝난 분석이면 현재 상태를 보낸 뒤 연결이 닫힌다
    public SseEmitter subscribeAnalysisStatus(Long analysisId) {
        if (!analysisJobRepository.existsById(analysisId)) {
            throw new CustomException(ErrorCode.ANALYSIS_NOT_FOUND);
        }

        // 구독을 먼저 등록한 뒤 상태를 읽어야 그 사이에 도착한 콜백을 놓치지 않는다
        SseEmitter emitter = sseEmitterManager.create(analysisId);
        AnalysisJob.Status status = analysisJobRepository.findById(analysisId)
                .map(AnalysisJob::getStatus)
                .orElseThrow(() -> new CustomException(ErrorCode.ANALYSIS_NOT_FOUND));
        sseEmitterManager.send(emitter, analysisId, status.name(), getStatusMessage(status));
        return emitter;
    }

    // 분석 이력 목록 조회, 요청 시각 최신순 페이징
    // mine은 내 이력만, all은 모든 사람의 이력이며 status를 주면 그 상태만 보여 준다
    @Transactional(readOnly = true)
    public AnalyzeHistoryResponse getAnalysisHistory(int page, int size, HistoryScope scope,
                                                     String status, String email) {
        User user = findUser(email);
        AnalysisJob.Status statusFilter = parseStatus(status);
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<AnalysisHistory> historyPage = scope == HistoryScope.MINE
                ? analysisHistoryRepository.findMine(user, statusFilter, pageRequest)
                : analysisHistoryRepository.findAllRequested(statusFilter, pageRequest);

        List<AnalyzeHistoryResponse.HistoryItem> history = historyPage.getContent().stream()
                .map(h -> {
                    AnalysisJob job = h.getAnalysisJob();
                    return AnalyzeHistoryResponse.HistoryItem.builder()
                            .analysisId(job.getAnalysisId())
                            .fileName(job.getFileName())
                            .status(job.getStatus().name())
                            .period(formatPeriod(job.getPeriodStartDate(), job.getPeriodEndDate()))
                            .submittedAt(job.getCreatedAt())
                            .requestedBy(h.getUser().getEmail())
                            .requestedAt(h.getRequestedAt())
                            .isMine(h.getUser().getUserId().equals(user.getUserId()))
                            .isMaskingEnabled(job.getIsMaskingEnabled())
                            .isTranslationEnabled(job.getIsTranslationEnabled())
                            .minClusterSize(job.getMinClusterSize())
                            .minSamples(job.getMinSamples())
                            .build();
                })
                .collect(Collectors.toList());

        return AnalyzeHistoryResponse.builder()
                .history(history)
                .totalCount(historyPage.getTotalElements())
                .totalPages(historyPage.getTotalPages())
                .currentPage(page)
                .build();
    }

    // 내 이력에서 분석 하나를 지운다, 분석 데이터와 다른 사람의 이력은 그대로 둔다
    @Transactional
    public void deleteAnalysisHistory(Long analysisId, String email) {
        User user = findUser(email);
        AnalysisHistory history = analysisHistoryRepository.findByUserAndAnalysisJob_AnalysisId(user, analysisId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANALYSIS_NOT_FOUND));
        analysisHistoryRepository.delete(history);
    }

    // 요청 이력 기록, 이미 있으면 요청 시각만 갱신한다
    private void recordHistory(User user, AnalysisJob job) {
        analysisHistoryRepository.findByUserAndAnalysisJob(user, job)
                .ifPresentOrElse(
                        AnalysisHistory::touch,
                        () -> analysisHistoryRepository.save(AnalysisHistory.builder()
                                .user(user)
                                .analysisJob(job)
                                .build()));
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    // 상태 필터 해석, 비어 있으면 전체이고 대소문자는 구분하지 않는다
    private static AnalysisJob.Status parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        for (AnalysisJob.Status s : AnalysisJob.Status.values()) {
            if (s.name().equalsIgnoreCase(status.trim())) return s;
        }
        throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
    }

    // AI 서버 콜백 수신, 진행 상태 갱신 또는 최종 분석 결과 적재
    // 중간 상태(PREPROCESSING, ANALYZING)와 실패는 상태만 갱신하고 종료한다
    @Transactional
    public void handleAnalysisCallback(Long analysisId, AnalysisCallbackRequest request) {
        AnalysisJob analysisJob = analysisJobRepository.findById(analysisId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANALYSIS_NOT_FOUND));

        // 이미 끝난 분석에 온 콜백은 다시 저장하지 않는다
        // AI 서버가 재전송으로 판단하지 않도록 오류 없이 정상 응답한다
        if (analysisJob.getStatus() == AnalysisJob.Status.COMPLETED
                || analysisJob.getStatus() == AnalysisJob.Status.FAIL) {
            log.warn("이미 종료된 분석의 콜백 무시: analysisId={}, 현재 상태={}, 수신 상태={}",
                    analysisId, analysisJob.getStatus(), request.getStatus());
            return;
        }

        // 중간 상태 콜백 (PREPROCESSING, ANALYZING)
        if ("PREPROCESSING".equals(request.getStatus())) {
            analysisJob.startPreprocessing();
            analysisJobRepository.save(analysisJob);
            sseEmitterManager.send(analysisId, "PREPROCESSING", "데이터 전처리 중입니다.");
            return;
        }

        if ("ANALYZING".equals(request.getStatus())) {
            analysisJob.startAnalyzing();
            analysisJobRepository.save(analysisJob);
            sseEmitterManager.send(analysisId, "ANALYZING", "AI 클러스터링 진행 중입니다."); 
            return;
        }

        // AI 서버는 실패 시 "FAILED"를 보내고 내부 상태값은 "FAIL"이므로 두 값을 모두 수용한다
        if ("FAIL".equals(request.getStatus()) || "FAILED".equals(request.getStatus())) {
            analysisJob.fail(request.getErrorMessage());
            analysisJobRepository.save(analysisJob);
            sseEmitterManager.send(analysisId, "FAIL", "분석에 실패했습니다.");
            log.error("분석 실패 콜백 수신: analysisId={}, error={}", analysisId, request.getErrorMessage());
            return;
        }

        // COMPLETED: 전체 데이터 적재
        // 1. 업로드 파일별 요약값과 날짜 통계 적재
        // 업로드 파일 범위의 값이라 서비스 전체 사용량(gold_usage_stat)에는 쓰지 않는다
        saveAnalysisSummary(analysisJob, request);
        saveDailyStats(analysisJob, request);

        // 2. 클러스터링 결과 적재
        if (request.getClusteringView() != null && request.getClusteringView().getClusterNames() != null) {
            for (AnalysisCallbackRequest.ClusteringViewResult.ClusterNameResult clusterResult
                    : request.getClusteringView().getClusterNames()) {
                Cluster cluster = Cluster.builder()
                        .analysisJob(analysisJob)
                        .clusterLabel(clusterResult.getClusterLabel())
                        .clusterName(clusterResult.getName())
                        .topKeywords(clusterResult.getTopKeywords())
                        .clusterSize(clusterResult.getSize())
                        .build();
                clusterRepository.save(cluster);
            }

            // points → ClusterLogMap 적재
            if (request.getClusteringView().getPoints() != null) {
                for (AnalysisCallbackRequest.ClusteringViewResult.PointResult point
                        : request.getClusteringView().getPoints()) {
                    Cluster cluster = clusterRepository
                            .findByAnalysisJobAndClusterLabel(analysisJob, point.getClusterLabel())
                            .orElse(null);

                    // cluster_id는 NOT NULL이므로 매칭 실패 시 적재하면 제약 위반으로 전체가 롤백된다.
                    // 해당 포인트만 건너뛰고 나머지 적재는 계속 진행한다
                    if (cluster == null) {
                        log.warn("클러스터 미매칭 포인트 제외: analysisId={}, clusterLabel={}",
                                analysisId, point.getClusterLabel());
                        continue;
                    }

                    ClusterLogMap clusterLogMap = ClusterLogMap.builder()
                            .cluster(cluster)
                            .sourceLogSeqNum(point.getSourceLogSeqNum())
                            .logType(ClusterLogMap.LogType.valueOf(point.getLogType()))
                            .umapX(point.getX() != null ? point.getX().floatValue() : null)
                            .umapY(point.getY() != null ? point.getY().floatValue() : null)
                            .logText(point.getText())
                            .build();
                    clusterLogMapRepository.save(clusterLogMap);
                }
            }
        }

        // 3. FAQ 후보 적재
        if (request.getFaqCandidates() != null) {
            for (AnalysisCallbackRequest.FaqCandidateResult candidateResult : request.getFaqCandidates()) {
                // 질문이나 답변이 빈 후보는 검토할 수 없으므로 저장하지 않는다
                if (isBlank(candidateResult.getStandardQuestion()) || isBlank(candidateResult.getAnswerDraft())) {
                    log.warn("질문 또는 답변이 빈 FAQ 후보 제외: analysisId={}, clusterLabel={}",
                            analysisId, candidateResult.getClusterLabel());
                    continue;
                }

                Cluster relatedCluster = null;
                if (candidateResult.getClusterLabel() != null) {
                    relatedCluster = clusterRepository
                            .findByAnalysisJobAndClusterLabel(analysisJob, candidateResult.getClusterLabel())
                            .orElse(null);
                }

                FaqCandidate candidate = FaqCandidate.builder()
                        .analysisJob(analysisJob)
                        .cluster(relatedCluster)
                        .candidateType(FaqCandidate.CandidateType.valueOf(candidateResult.getCandidateType()))
                        .standardQuestion(candidateResult.getStandardQuestion())
                        .answerDraft(candidateResult.getAnswerDraft())
                        .qType(candidateResult.getQType())
                        .category(candidateResult.getCategory())
                        .similarQuestions(candidateResult.getSimilarQuestions())
                        .occurrenceCount(candidateResult.getOccurrenceCount())
                        .representativeKeywords(candidateResult.getRepresentativeKeywords())
                        .build();
                faqCandidateRepository.save(candidate);

                if (candidateResult.getSynonyms() != null) {
                        for (AnalysisCallbackRequest.SynonymResult synonymResult : candidateResult.getSynonyms()) {
                            if (synonymCandidateRepository.existsByCandidateCandidateIdAndSynonymText(
                                    candidate.getCandidateId(), synonymResult.getText())) {
                                continue;
                            }
                            SynonymCandidate synonym = SynonymCandidate.builder()
                                    .candidate(candidate)
                                    .synonymText(synonymResult.getText())
                                    .synonymType(synonymResult.getType() != null
                                            ? SynonymCandidate.SynonymType.valueOf(synonymResult.getType())
                                            : null)
                                    .build();
                            synonymCandidateRepository.save(synonym);
                        }
                    }

                if (candidateResult.getMatchedFaqs() != null) {
                    for (AnalysisCallbackRequest.MatchedFaqResult matchedFaq : candidateResult.getMatchedFaqs()) {
                        FaqCandidateMatch match = FaqCandidateMatch.builder()
                                .candidate(candidate)
                                .matchedFaqSeqNum(matchedFaq.getMatchedFaqSeqNum())
                                .matchScore(BigDecimal.valueOf(matchedFaq.getMatchScore()))
                                .build();
                        faqCandidateMatchRepository.save(match);
                    }
                }
            }
        }

        // 4. 미답변 사유별 통계 적재
        if (request.getUnanswerAnalysis() != null && request.getUnanswerAnalysis().getByReason() != null) {
            for (AnalysisCallbackRequest.UnanswerAnalysisResult.ReasonItemResult item
                    : request.getUnanswerAnalysis().getByReason()) {
                UnansweredReason reason = UnansweredReason.fromDescription(item.getReason());

                UnansweredStat stat = UnansweredStat.builder()
                        .analysisJob(analysisJob)
                        .reason(reason)
                        .unanswerCount(item.getCount())
                        .build();
                unansweredStatRepository.save(stat);
            }
        }

        // 5. 급증 탐지 적재
        if (request.getSurgeDetection() != null) {
            for (AnalysisCallbackRequest.SurgeDetectionResult surgeResult : request.getSurgeDetection()) {
                Cluster relatedCluster = null;
                if (surgeResult.getRelatedClusterLabel() != null) {
                    relatedCluster = clusterRepository
                            .findByAnalysisJobAndClusterLabel(analysisJob, surgeResult.getRelatedClusterLabel())
                            .orElse(null);
                }

                SurgeDetection surgeDetection = SurgeDetection.builder()
                        .analysisJob(analysisJob)
                        .keyword(surgeResult.getKeyword())
                        .isNew(surgeResult.getIsNew())
                        .increasedRate(surgeResult.getIncreasedRate() != null
                                ? BigDecimal.valueOf(surgeResult.getIncreasedRate()) : null)
                        .keywordCount(surgeResult.getKeywordCount())
                        .relatedCluster(relatedCluster)
                        .build();
                surgeDetectionRepository.save(surgeDetection);

                if (surgeResult.getPeriodStats() != null) {
                    for (AnalysisCallbackRequest.SurgeDetectionResult.SurgeStatResult surgeStatResult
                            : surgeResult.getPeriodStats()) {
                        LocalDate statDate = LocalDate.parse(surgeStatResult.getStatDate());

                        StatDate statDateEntity = statDateRepository.findById(statDate)
                                .orElseGet(() -> statDateRepository.save(
                                        StatDate.builder().statDate(statDate).build()));

                        SurgeStat surgeStat = SurgeStat.builder()
                                .surgeDetection(surgeDetection)
                                .statDate(statDateEntity)
                                .periodType(SurgeStat.PeriodType.valueOf(surgeStatResult.getPeriodType()))
                                .count(surgeStatResult.getCount())
                                .build();
                        surgeStatRepository.save(surgeStat);
                    }
                }
            }
        }

        // 6. 성능 비교 적재
        if (request.getEvaluation() != null) {
                AnalysisCallbackRequest.EvaluationResult eval = request.getEvaluation();
                LocalDate beforeStatDate = LocalDate.parse(eval.getBeforeStatDate());
            
                UsageStat beforeUsageStat = usageStatRepository
                        .findByStatDate_StatDate(beforeStatDate)
                        .orElse(null);
            
                if (beforeUsageStat == null) {
                    log.warn("beforeStatDate에 해당하는 사용량 통계 없음: {}", beforeStatDate);
                } else {
                    PerformanceComparison comparison = PerformanceComparison.builder()
                            .analysisJob(analysisJob)
                            .usageStat(beforeUsageStat)
                            .afterUnanswerCnt(eval.getAfterUnanswerCount())
                            .afterUnanswerRate(eval.getAfterUnanswerRate() != null
                                    ? BigDecimal.valueOf(eval.getAfterUnanswerRate()) : null)
                            .accuracyGain(eval.getAccuracyGain() != null
                                    ? BigDecimal.valueOf(eval.getAccuracyGain()) : null)
                            .falsePositiveRate(eval.getFalsePositiveRate() != null
                                    ? BigDecimal.valueOf(eval.getFalsePositiveRate()) : null)
                            .evalThreshold(eval.getEvalThreshold() != null
                                    ? BigDecimal.valueOf(eval.getEvalThreshold()) : null)
                            .resolvedCountByAi(eval.getResolvedCountByAI())
                            .build();
                    performanceComparisonRepository.save(comparison);
                }
            }
            
            // 7. 완료 처리
            analysisJob.finish();
            analysisJobRepository.save(analysisJob);
            sseEmitterManager.send(analysisId, "COMPLETED", "분석이 완료되었습니다.");
            log.info("분석 완료 콜백 처리 완료: analysisId={}", analysisId);
    }

    // FAQ 후보를 응답 DTO로 변환, 유사어와 매칭 정보 포함
    private AnalyzeResultResponse.FaqCandidate buildFaqCandidateItem(FaqCandidate candidate) {
        List<AnalyzeResultResponse.FaqCandidate.SynonymItem> synonyms =
                synonymCandidateRepository
                        .findByCandidateCandidateId(candidate.getCandidateId())
                        .stream()
                        .map(s -> AnalyzeResultResponse.FaqCandidate.SynonymItem.builder()
                                .text(s.getSynonymText())
                                .type(s.getSynonymType() != null ? s.getSynonymType().name() : null)
                                .build())
                        .collect(Collectors.toList());
    
        List<AnalyzeResultResponse.FaqCandidate.MatchedFaq> matchedFaqs =
                faqCandidateMatchRepository
                        .findByCandidateCandidateIdOrderByMatchScoreDesc(candidate.getCandidateId())
                        .stream()
                        .map(m -> AnalyzeResultResponse.FaqCandidate.MatchedFaq.builder()
                                .matchedFaqSeqNum(m.getMatchedFaqSeqNum())
                                .matchScore(m.getMatchScore().doubleValue())
                                .build())
                        .collect(Collectors.toList());
    
        return AnalyzeResultResponse.FaqCandidate.builder()
                .candidateId(candidate.getCandidateId())
                .clusterLabel(candidate.getCluster() != null
                        ? candidate.getCluster().getClusterLabel() : -1)
                .candidateType(candidate.getCandidateType().name())
                .qType(candidate.getQType())
                .category(candidate.getCategory())
                .standardQuestion(candidate.getStandardQuestion())
                .similarQuestions(candidate.getSimilarQuestions())
                
                .representativeKeywords(candidate.getRepresentativeKeywords()) 
                
                .answerDraft(candidate.getAnswerDraft())
                .synonyms(synonyms)
                .occurrenceCount(candidate.getOccurrenceCount() != null
                        ? candidate.getOccurrenceCount() : 0)
                .matchedFaqs(matchedFaqs)
                .build();
    }

    // 클러스터 로그를 산점도 좌표로 변환
    private AnalyzeResultResponse.ClusteringView.Point buildPoint(ClusterLogMap l) {
        return AnalyzeResultResponse.ClusteringView.Point.builder()
                .x(l.getUmapX() != null ? l.getUmapX() : 0.0)
                .y(l.getUmapY() != null ? l.getUmapY() : 0.0)
                .clusterLabel(l.getCluster() != null ? l.getCluster().getClusterLabel() : -1)
                .text(l.getLogText())
                .sourceLogSeqNum(l.getSourceLogSeqNum())
                .logType(l.getLogType().name())
                .build();
    }

    // 클러스터 메타 정보를 응답 DTO로 변환
    private AnalyzeResultResponse.ClusteringView.ClusterName buildClusterName(Cluster c) {
        return AnalyzeResultResponse.ClusteringView.ClusterName.builder()
                .clusterId(c.getClusterId())
                .clusterLabel(c.getClusterLabel())
                .name(c.getClusterName())
                .topKeywords(c.getTopKeywords()) 
                .size(c.getClusterSize() != null ? c.getClusterSize() : 0)
                .build();
    }

    // 상태별 사용자 안내 문구 반환
    private String getStatusMessage(AnalysisJob.Status status) {
        return switch (status) {
            case PENDING -> "분석 대기 중입니다.";
            case PREPROCESSING -> "데이터 전처리 중입니다.";
            case ANALYZING -> "현재 AI 클러스터링을 진행 중입니다. 잠시만 기다려 주세요.";
            case COMPLETED -> "분석이 완료되었습니다.";
            case FAIL -> "분석에 실패했습니다.";
        };
    }

    // AI가 보낸 파일별 요약값 저장, 보내지 않았으면 비워 두고 조회 시 기존 방식으로 계산한다
    // 합계가 맞지 않아도 분석이 끝나지 않는 일이 없도록 거절하지 않고 경고만 남긴다
    private void saveAnalysisSummary(AnalysisJob analysisJob, AnalysisCallbackRequest request) {
        AnalysisCallbackRequest.SystemStatusResult system = request.getSystemStatus();
        AnalysisCallbackRequest.PerformanceMetricsResult metrics = request.getPerformanceMetrics();
        if (system == null || system.getTotalLogCount() == null) {
            return;
        }
        AnalysisCallbackRequest.SystemStatusResult.SamplingStatusResult sampling = system.getSamplingStatus();
        Integer correct = sampling != null ? sampling.getCorrect() : null;
        Integer lowQuality = sampling != null ? sampling.getLowQuality() : null;
        Integer unanswer = sampling != null ? sampling.getUnanswer() : null;
        if (zeroIfNull(correct) + zeroIfNull(lowQuality) + zeroIfNull(unanswer) != system.getTotalLogCount()) {
            log.warn("요약값 합계 불일치: analysisId={}, total={}, correct={}, lowQuality={}, unanswer={}",
                    analysisJob.getAnalysisId(), system.getTotalLogCount(), correct, lowQuality, unanswer);
        }
        analysisJob.recordSummary(system.getTotalLogCount(), correct, lowQuality, unanswer,
                metrics != null ? toPercent(metrics.getCurrentUnanswerRate()) : null,
                metrics != null ? toPercent(metrics.getPredictedAccuracyGain()) : null,
                metrics != null ? metrics.getResolvedCountByAI() : null);
    }

    // 파일별 날짜 통계 저장, usageStats가 없는 예전 형식은 trend로 저품질·미답변 건수만 채운다
    // 같은 날짜가 두 번 오면 나중 값을 쓴다 (분석 ID·날짜는 하나만 저장된다)
    private void saveDailyStats(AnalysisJob analysisJob, AnalysisCallbackRequest request) {
        Map<LocalDate, AnalysisDailyStat> byDate = new LinkedHashMap<>();
        if (request.getUsageStats() != null && !request.getUsageStats().isEmpty()) {
            for (AnalysisCallbackRequest.UsageStatResult usage : request.getUsageStats()) {
                LocalDate statDate = parseTrendDate(usage.getStatDate(), analysisJob);
                byDate.put(statDate, AnalysisDailyStat.builder()
                        .analysisJob(analysisJob)
                        .statDate(statDate)
                        .totalCnt(usage.getTotalCnt())
                        .answerCnt(usage.getAnswerCnt())
                        .lowQualityCount(usage.getLowQualityCount())
                        .unanswerCnt(usage.getUnanswerCnt())
                        .build());
            }
        } else if (request.getTrend() != null) {
            for (AnalysisCallbackRequest.TrendItemResult trendItem : request.getTrend()) {
                LocalDate statDate = parseTrendDate(trendItem.getDate(), analysisJob);
                byDate.put(statDate, AnalysisDailyStat.builder()
                        .analysisJob(analysisJob)
                        .statDate(statDate)
                        .lowQualityCount(trendItem.getLowQualityCount())
                        .unanswerCnt(trendItem.getUnanswerCount())
                        .build());
            }
        }
        analysisDailyStatRepository.saveAll(byDate.values());
    }

    // 파일별 날짜 통계를 날짜마다 합산, 전체·정상 건수는 값이 있는 것만 더하고 하나도 없으면 null
    private List<AnalyzeResultResponse.UsageStatItem> sumDailyStats(List<AnalysisDailyStat> dailyStats) {
        Map<LocalDate, List<AnalysisDailyStat>> byDate = dailyStats.stream()
                .collect(Collectors.groupingBy(AnalysisDailyStat::getStatDate, TreeMap::new, Collectors.toList()));
        return byDate.entrySet().stream()
                .map(e -> AnalyzeResultResponse.UsageStatItem.builder()
                        .statDate(e.getKey().toString())
                        .totalCnt(sumNullable(e.getValue(), AnalysisDailyStat::getTotalCnt))
                        .answerCnt(sumNullable(e.getValue(), AnalysisDailyStat::getAnswerCnt))
                        .lowQualityCount(e.getValue().stream().mapToInt(AnalysisDailyStat::getLowQualityCount).sum())
                        .unanswerCnt(e.getValue().stream().mapToInt(AnalysisDailyStat::getUnanswerCnt).sum())
                        .build())
                .collect(Collectors.toList());
    }

    private Integer sumNullable(List<AnalysisDailyStat> stats, Function<AnalysisDailyStat, Integer> getter) {
        List<Integer> values = stats.stream().map(getter).filter(Objects::nonNull).toList();
        return values.isEmpty() ? null : values.stream().mapToInt(Integer::intValue).sum();
    }

    // AI는 백분율을 소수 6자리까지 보내므로 저장 칸(소수 2자리)에 맞춰 반올림한다
    private BigDecimal toPercent(Double value) {
        return value != null ? BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP) : null;
    }

    private int countLogs(List<ClusterLogMap> logs, ClusterLogMap.LogType type) {
        return (int) logs.stream().filter(l -> l.getLogType() == type).count();
    }

    private int zeroIfNull(Integer value) {
        return value != null ? value : 0;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // 분석 기간 표시, 기간이 없으면 null이고 한쪽만 있으면 그쪽만 쓴다
    private String formatPeriod(LocalDate start, LocalDate end) {
        if (start == null && end == null) return null;
        if (start == null) return "~ " + end;
        if (end == null) return start + " ~";
        return start + " ~ " + end;
    }

    // 콜백 트렌드 날짜 파싱
    // "MM-DD" 형식으로 연도가 없으면 분석 대상 기간의 연도를 사용한다.
    // 현재 연도를 쓰면 과거 기간을 분석할 때 통계가 엉뚱한 날짜에 적재된다
    private LocalDate parseTrendDate(String rawDate, AnalysisJob analysisJob) {
        String[] parts = rawDate.split("-");

        if (parts.length == 3) {
            return LocalDate.of(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        }

        int year = analysisJob.getPeriodStartDate() != null
                ? analysisJob.getPeriodStartDate().getYear()
                : LocalDate.now().getYear();

        return LocalDate.of(year, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    // JSON 문자열로 저장된 키워드를 리스트로 변환, 실패 시 빈 리스트 반환
    private List<String> parseKeywords(String json) {
        if (json == null) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("키워드 파싱 실패: {}", json);
            return List.of();
        }
    }
}
