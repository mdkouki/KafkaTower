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
            // Anchored to an imperative addressed at the assistant ("you are now X", "act as if
            // you were X") rather than a bare "act as" — which also matches ordinary Kafka
            // phrasing like "which services act as producers for X".
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
            // "database"/"read" dropped from this combo — both are ordinary Kafka-domain words
            // ("which services read database change topics") and produced routine false
            // positives. Kept: attempts to reach the filesystem/shell/OS, not the data itself.
            Pattern.compile("(?i)(access|open|execute|run|write\\s+to)\\s+(file|filesystem|disk|shell|terminal|command)"),
            Pattern.compile("(?i)(exec|eval|system|popen|subprocess|runtime\\.exec|processbuilder)\\s*\\("),
            Pattern.compile("(?i)(;\\s*(ls|cat|rm|wget|curl|bash|sh|python|java)|&&\\s*(ls|cat|rm|wget|curl|bash))"),
            // Backtick-delimited spans dropped — that's how operators naturally write topic/group
            // names in prose ("why is `orders-consumer-group` lagging?"), not an injection vector
            // on its own. Template-literal injection (${...}) is still caught.
            Pattern.compile("(?i)\\$\\{.*}"),
            Pattern.compile("(?i)(union\\s+select|select\\s+\\*\\s+from|drop\\s+table|insert\\s+into|delete\\s+from|--\\s|;\\s*--)"),
            // Bare "1=1" dropped — it can appear inside legitimate strings/config values with no
            // SQL context; the quoted/OR-prefixed variants below are the actual injection shapes.
            Pattern.compile("(?i)(or\\s+'?1'?\\s*=\\s*'?1|' or ''='|admin'--)")
    );

    public static final String REJECTION_MESSAGE =
            "I cannot process this request. It appears to contain content that violates security policies. " +
            "Please ask a question related to Kafka resources or applications.";

    /**
     * Intercepts every ChatService.askAgent() call and rejects prompt-injection attempts
     * before the message reaches the agent. askAgent returns a Flux&lt;String&gt; that isn't
     * subscribed to until after the SSE emitter has already been returned to the client
     * (see ChatController), so a rejection must short-circuit to a Flux, not throw: throwing
     * here escapes as an uncaught exception from the controller method itself — before the
     * SseEmitter is even created — and surfaces as a bare HTTP 500 with no rejection message.
     */
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
