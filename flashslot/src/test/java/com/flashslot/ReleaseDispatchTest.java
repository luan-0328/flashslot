package com.flashslot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.messaging.model.*;
import com.flashslot.waitlist.mapper.ReleaseWorkMapper;
import com.flashslot.waitlist.service.ReleaseDispatchService;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReleaseDispatchTest {
    @Test void compensationSkipsJobsWithInFlightOrRecentDispatch() {
        var work=mock(ReleaseWorkMapper.class);
        var events=mock(com.flashslot.messaging.mapper.OutboxEventMapper.class);
        var now=Instant.parse("2026-10-15T02:01:30Z");
        var clock=Clock.fixed(now,ZoneOffset.UTC);
        when(work.findDispatchable(now,now.minus(ReleaseDispatchService.REDISPATCH_AFTER))).thenReturn(List.of());
        assertThat(new ReleaseDispatchService(work,events,new ObjectMapper(),clock).scan()).isZero();
        verify(work).findDispatchable(now,Instant.parse("2026-10-15T01:59:30Z"));
        verifyNoInteractions(events);
    }

    @Test void dispatchUsesOneEventIdPerJobAndMinute() {
        var events=mock(com.flashslot.messaging.mapper.OutboxEventMapper.class);
        var captor=org.mockito.ArgumentCaptor.forClass(com.flashslot.messaging.entity.OutboxEvent.class);
        when(events.insertReleaseDispatch(captor.capture())).thenReturn(1);
        var service=new ReleaseDispatchService(mock(ReleaseWorkMapper.class),events,new ObjectMapper(),Clock.systemUTC());
        service.dispatch(42,Instant.parse("2026-10-15T02:01:05Z"));
        service.dispatch(42,Instant.parse("2026-10-15T02:01:59Z"));
        service.dispatch(42,Instant.parse("2026-10-15T02:02:00Z"));
        var ids=captor.getAllValues().stream().map(com.flashslot.messaging.entity.OutboxEvent::getEventId).toList();
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(ids.get(2)).isNotEqualTo(ids.get(0));
        assertThat(captor.getValue().getAggregateId()).isEqualTo("42");
        assertThat(captor.getValue().getEventType()).isEqualTo(PublishedEvent.RELEASE);
    }

    @Test void resumedDispatchHasStableIdentityPerResourceVersionAndNeverReusesMinuteEvent() {
        var work=mock(ReleaseWorkMapper.class);
        when(work.findPendingForResource(7L)).thenReturn(List.of(42L));
        var events=mock(com.flashslot.messaging.mapper.OutboxEventMapper.class);
        var captor=org.mockito.ArgumentCaptor.forClass(com.flashslot.messaging.entity.OutboxEvent.class);
        when(events.insertReleaseDispatch(captor.capture())).thenReturn(1);
        var service=new ReleaseDispatchService(work,events,new ObjectMapper(),Clock.systemUTC());
        var now=Instant.parse("2026-10-16T00:49:00Z");
        service.dispatch(42,now);
        service.dispatchOnResume(7,1,now.plusSeconds(5));
        service.dispatchOnResume(7,1,now.plusSeconds(10));
        service.dispatchOnResume(7,3,now.plusSeconds(15));
        var ids=captor.getAllValues().stream().map(com.flashslot.messaging.entity.OutboxEvent::getEventId).toList();
        assertThat(ids.get(1)).isNotEqualTo(ids.get(0)).isEqualTo(ids.get(2));
        assertThat(ids.get(3)).isNotEqualTo(ids.get(1));
    }

    @Test void dispatchEnvelopeCannotBeMistakenForANotificationOrAnotherJob() {
        var json=new ObjectMapper();
        var payload=json.createObjectNode().put("schemaVersion",1).put("jobId",42);
        var event=new NotificationEvent(java.util.UUID.randomUUID().toString(),"RELEASE_DISPATCHED","42",Instant.now(),payload);
        assertThatCode(() -> PublishedEvent.validate(event)).doesNotThrowAnyException();
        assertThatThrownBy(event::validate).isInstanceOf(IllegalArgumentException.class);
        var forged=new NotificationEvent(event.eventId(),event.eventType(),"43",event.occurredAt(),payload);
        assertThatThrownBy(() -> PublishedEvent.validate(forged)).isInstanceOf(IllegalArgumentException.class);
    }
}
