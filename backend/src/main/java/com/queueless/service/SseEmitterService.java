package com.queueless.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class SseEmitterService {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterService.class);
    private static final Long SSE_TIMEOUT = 30 * 60 * 1000L; // 30 minutes

    // Owner emitters by Shop ID
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> ownerEmitters = new ConcurrentHashMap<>();

    // Customer tracking emitters by trackingToken
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> customerEmitters = new ConcurrentHashMap<>();

    public SseEmitter createOwnerEmitter(Long shopId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        ownerEmitters.computeIfAbsent(shopId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeOwnerEmitter(shopId, emitter));
        emitter.onTimeout(() -> removeOwnerEmitter(shopId, emitter));
        emitter.onError(e -> removeOwnerEmitter(shopId, emitter));

        // Initial connect ping
        try {
            emitter.send(SseEmitter.event()
                    .name("INIT")
                    .data("Connected to QueueLess Owner Stream", MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            removeOwnerEmitter(shopId, emitter);
        }

        return emitter;
    }

    public SseEmitter createCustomerEmitter(String trackingToken) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        customerEmitters.computeIfAbsent(trackingToken, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeCustomerEmitter(trackingToken, emitter));
        emitter.onTimeout(() -> removeCustomerEmitter(trackingToken, emitter));
        emitter.onError(e -> removeCustomerEmitter(trackingToken, emitter));

        // Initial connect ping
        try {
            emitter.send(SseEmitter.event()
                    .name("INIT")
                    .data("Connected to QueueLess Customer Stream", MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            removeCustomerEmitter(trackingToken, emitter);
        }

        return emitter;
    }

    private void removeOwnerEmitter(Long shopId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = ownerEmitters.get(shopId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                ownerEmitters.remove(shopId);
            }
        }
    }

    private void removeCustomerEmitter(String trackingToken, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = customerEmitters.get(trackingToken);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                customerEmitters.remove(trackingToken);
            }
        }
    }

    public boolean hasOwnerEmitters(Long shopId) {
        CopyOnWriteArrayList<SseEmitter> list = ownerEmitters.get(shopId);
        return list != null && !list.isEmpty();
    }

    public boolean hasCustomerEmitters(String trackingToken) {
        CopyOnWriteArrayList<SseEmitter> list = customerEmitters.get(trackingToken);
        return list != null && !list.isEmpty();
    }

    public void notifyOwner(Long shopId, String eventName, Object data) {
        CopyOnWriteArrayList<SseEmitter> list = ownerEmitters.get(shopId);
        if (list == null || list.isEmpty()) return;

        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data, MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                deadEmitters.add(emitter);
            }
        }

        if (!deadEmitters.isEmpty()) {
            list.removeAll(deadEmitters);
        }
    }

    public void notifyCustomer(String trackingToken, String eventName, Object data) {
        CopyOnWriteArrayList<SseEmitter> list = customerEmitters.get(trackingToken);
        if (list == null || list.isEmpty()) return;

        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data, MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                deadEmitters.add(emitter);
            }
        }

        if (!deadEmitters.isEmpty()) {
            list.removeAll(deadEmitters);
        }
    }
}
