package com.lynra.kafkatower.utils;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.regex.Pattern;

@Component
@Aspect
public class PromptSecurityValidator {

    private static final Logger log = LoggerFactory.getLogger(PromptSecurityValidator.class);

    public static final List<Pattern> THREAT_PATTERNS = List.of(
            Pattern.compile("(?i)(ignore|forget|disregard|override|bypass)\\s+(your\\s+)?(previous\\s+|all\\s+)?(instructions?|rules?|guidelines?|constraints?|prompt)"),
            // Anchored to an imperative addressed at the assistant: a bare "act as" also matches
            // ordinary Kafka phrasing like "which services act as producers for X".
            Pattern.compile("(?i)\\b(you\\s+are\\s+now|you\\s+(should\\s+|will\\s+)?act\\s+as|pretend\\s+(you\\s+are|to\\s+be)|behave\\s+as|roleplay\\s+as|impersonate)\\b"),
            Pattern.compile("(?i)\\b(DAN|jailbreak|do\\s+anything\\s+now|developer\\s+mode|god\\s+mode|unrestricted\\s+mode)\\b"),
            Pattern.compile("(?i)(your\\s+(true|real|actual)\\s+(self|purpose|goal|identity)|you\\s+have\\s+no\\s+(restrictions?|limits?|rules?))"),
            Pattern.compile("(?i)(simulate|hypothetically|for\\s+(educational|research|fictional)\\s+purposes?).{0,60}(hack|exploit|inject|bypass|override)"),
            Pattern.compile("(?i)(new\\s+system\\s+prompt|system\\s+prompt\\s+is\\s+now|updated\\s+(instructions?|prompt))"),
            Pattern.compile("(?i)<(system|assistant|user|human|ai|instruction)\\s*>"),
            Pattern.compile("(?i)\\[(system|assistant|inst|s?instruction)\\]"),
            Pattern.compile("(?i)(###\\s*(system|instruction)|\\*\\*\\s*(system|new\\s+task)\\s*\\*\\*)"),
            Pattern.compile("(?i)(user\\s*:\\s*ignore|assistant\\s*:\\s*sure|human\\s*:\\s*disregard)"),
            Pattern.compile("(?i)(show\\s+me|print|reveal|dump|output|return|display)\\s+(your\\s+)?(system\\s+prompt|instructions?|context|configuration)"),
            Pattern.compile("(?i)(what\\s+are\\s+your\\s+instructions?|repeat\\s+(your\\s+)?(prompt|instructions?))"),
            // "database"/"read" are ordinary Kafka-domain words and caused false positives; only
            // attempts to reach the filesystem/shell/OS are matched.
            Pattern.compile("(?i)(access|open|execute|run|write\\s+to)\\s+(file|filesystem|disk|shell|terminal|command)"),
            Pattern.compile("(?i)(exec|eval|system|popen|subprocess|runtime\\.exec|processbuilder)\\s*\\("),
            Pattern.compile("(?i)(;\\s*(ls|cat|rm|wget|curl|bash|sh|python|java)|&&\\s*(ls|cat|rm|wget|curl|bash))"),
            // Backtick spans are deliberately not matched: operators write topic/group names that way.
            Pattern.compile("(?i)\\$\\{.*}"),
            Pattern.compile("(?i)(union\\s+select|select\\s+\\*\\s+from|drop\\s+table|insert\\s+into|delete\\s+from|--\\s|;\\s*--)"),
            // Bare "1=1" is not matched: it appears in legitimate config values.
            Pattern.compile("(?i)(or\\s+'?1'?\\s*=\\s*'?1|' or ''='|admin'--)")
    );

    public static final String REJECTION_MESSAGE =
            "I cannot process this request. It appears to contain content that violates security policies. " +
            "Please ask a question related to Kafka resources or applications.";

    // askAgent returns a Flux that is subscribed only after the SseEmitter has been returned, so a
    // rejection must short-circuit to a Flux: throwing would surface as a bare HTTP 500.
    @Around("execution(* com.lynra.kafkatower.chatbot.ChatService.askAgent(..)) && args(sessionId, message)")
    public Object validateUserMessage(ProceedingJoinPoint joinPoint, String sessionId, String message) throws Throwable {
        if (isThreat(message)) {
            return Flux.just(REJECTION_MESSAGE);
        }
        return joinPoint.proceed();
    }

    public static boolean isThreat(String input) {
        if (input == null || input.isBlank()) return false;
        for (Pattern pattern : THREAT_PATTERNS) {
            if (pattern.matcher(input).find()) {
                log.warn("Security threat detected [pattern={}] input={}", pattern.pattern(), sanitize(input));
                return true;
            }
        }
        return false;
    }

    public static String sanitize(String input) {
        if (input == null) return "";
        String s = input.replaceAll("[\r\n\t]", " ");
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
