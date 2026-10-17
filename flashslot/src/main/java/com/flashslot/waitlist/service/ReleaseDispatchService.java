package com.flashslot.waitlist.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.messaging.entity.OutboxEvent;
import com.flashslot.messaging.mapper.OutboxEventMapper;
import com.flashslot.messaging.model.PublishedEvent;
import com.flashslot.waitlist.mapper.ReleaseWorkMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 只可靠派发，不占用调度线程执行撮合。任务创建时立即派发，补偿扫描只补漏。 */
@Service @RequiredArgsConstructor
public class ReleaseDispatchService {
    /** 已发布但任务仍到期未推进时，等待该时长再补派，覆盖消息丢失、预算耗尽等情况。 */
    public static final Duration REDISPATCH_AFTER = Duration.ofMinutes(2);

    private final ReleaseWorkMapper work;
    private final OutboxEventMapper events;
    private final ObjectMapper json;
    private final Clock clock;

    /** 补偿：到期 PENDING 且没有在途或近期派发事件的任务才补派，broker 故障期间不会堆积重复指令。 */
    public int scan() {
        Instant now=clock.instant();
        int created=0;
        for(long id:work.findDispatchable(now,now.minus(REDISPATCH_AFTER))) created+=dispatch(id,now);
        return created;
    }

    /**
     * 与创建或重置任务处于同一事务时，事件随任务一起提交，由发布器秒级投递。
     * 同一任务同一分钟使用相同事件 ID，多实例或重复调用只生成一条。
     */
    public int dispatch(long jobId,Instant now) {
        Instant minute=now.truncatedTo(ChronoUnit.MINUTES);
        return dispatch(jobId,now,"release-v1:"+jobId+":"+minute);
    }

    /**
     * 调用方持有设备排他锁并已将 PENDING 任务改为到期；与设备恢复同事务提交。
     * 每次恢复以设备版本区分，避开旧消息冷却与同分钟 Inbox，同时同一次恢复仍可防重。
     */
    @Transactional(propagation=Propagation.MANDATORY)
    public void dispatchOnResume(long resourceId,long resourceVersion,Instant now) {
        for(long jobId:work.findPendingForResource(resourceId))
            dispatch(jobId,now,"release-resume-v1:"+resourceId+":"+resourceVersion+":"+jobId);
    }

    private int dispatch(long jobId,Instant now,String identity) {
        var event=new OutboxEvent();
        event.setEventId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString());
        event.setEventType(PublishedEvent.RELEASE);
        event.setAggregateId(Long.toString(jobId));
        event.setPayload(json.createObjectNode().put("schemaVersion",1).put("jobId",jobId));
        event.setCreatedAt(now);
        event.setNextRunAt(now);
        return events.insertReleaseDispatch(event);
    }
}
