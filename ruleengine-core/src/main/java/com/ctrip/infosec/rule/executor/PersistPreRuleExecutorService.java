package com.ctrip.infosec.rule.executor;

import com.ctrip.infosec.common.model.RiskFact;
import com.ctrip.infosec.configs.Caches;
import com.ctrip.infosec.configs.Configs;
import com.ctrip.infosec.configs.event.PersistPreRule;
import com.ctrip.infosec.configs.event.PostRule;
import com.ctrip.infosec.configs.rule.trace.logger.TraceLogger;
import com.ctrip.infosec.rule.Contexts;
import com.ctrip.infosec.rule.engine.StatelessPersistPreRuleEngine;
import com.ctrip.infosec.rule.engine.StatelessPostRuleEngine;
import com.ctrip.infosec.sars.util.Collections3;
import com.ctrip.infosec.sars.util.SpringContextHolder;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by yxjiang on 2015/7/15.
 */
@Service
public class PersistPreRuleExecutorService {
    private static final Logger logger = LoggerFactory.getLogger(PersistPreRuleExecutorService.class);

    /**
     * 执行规则
     */
    public RiskFact executePreRules(RiskFact fact, boolean isAsync) {
        execute(fact, isAsync);
        return fact;
    }

    /**
     * 串行执行
     */
    void execute(RiskFact fact, boolean isAsync) {

        // matchRules
        List<PersistPreRule> matchedRules = matchRules(fact);
        List<String> scriptRulePackageNames = Collections3.extractToList(matchedRules, "eventPoint");
        logger.debug(Contexts.getLogPrefix() + "matched post rules: " + StringUtils.join(scriptRulePackageNames, ", "));
        TraceLogger.traceLog("匹配到 " + matchedRules.size() + " 条落地数据准备规则 ...");

        StatelessPersistPreRuleEngine statelessPersistPreRuleEngine = SpringContextHolder.getBean(StatelessPersistPreRuleEngine.class);
        for (PersistPreRule rule : matchedRules) {
            TraceLogger.beginNestedTrans(fact.eventId);
            TraceLogger.setNestedLogPrefix("[" + rule.getEventPoint() + "]");
            try {
                long start = System.currentTimeMillis();

                statelessPersistPreRuleEngine.execute(rule.getId(), fact);

                long handlingTime = System.currentTimeMillis() - start;
                if (handlingTime > 100) {
                    logger.info(Contexts.getLogPrefix() + "persistPreRule: " + rule.getEventPoint() + ", usage: " + handlingTime + "ms");
                }
                TraceLogger.traceLog("[" + rule.getEventPoint() + "] usage: " + handlingTime + "ms");

            } catch (Throwable ex) {
                logger.warn(Contexts.getLogPrefix() + "invoke stateless persist pre-rule failed. persistPreRule: " + rule.getEventPoint(), ex);
                TraceLogger.traceLog("[" + rule.getEventPoint() + "] EXCEPTION: " + ex.toString());
            } finally {
                TraceLogger.commitNestedTrans();
            }
        }

    }

    private List<PersistPreRule> matchRules(final RiskFact fact) {
        TraceLogger.traceLog("数据准备规则列表：" + !CollectionUtils.isEmpty(Caches.persistPreRuleConfigs));
        if(CollectionUtils.isEmpty(Caches.persistPreRuleConfigs)){
            return ListUtils.EMPTY_LIST;
        }
        final String eventPoint = fact.getEventPoint();
        TraceLogger.traceLog("报文eventPoint：" + eventPoint);
        return Lists.newArrayList(Collections2.filter(Caches.persistPreRuleConfigs, new Predicate<PersistPreRule>() {
            @Override
            public boolean apply(PersistPreRule input) {
                return input.getEventPoint().equals(eventPoint) && Configs.match(input.getConditions(), input.getConditionsLogical(), fact.eventBody);
            }
        }));
    }
}
