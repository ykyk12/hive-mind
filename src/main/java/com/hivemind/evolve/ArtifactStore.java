package com.hivemind.evolve;

import com.hivemind.change.ChangeKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 制品库：每次生效的变更都留一条不可变记录，并维护"当前生效指针"。
 *
 * 回滚 = 把指针指回上一条记录，因此"改坏了自己"永远是可恢复的。
 */
@Slf4j
@Component
public class ArtifactStore {

    public record ArtifactRecord(String artifactId,
                                 String name,
                                 String ref,
                                 ChangeKind kind,
                                 String location,
                                 int version,
                                 long createdAtMillis) {
    }

    private final List<ArtifactRecord> history = new CopyOnWriteArrayList<>();
    private final Map<String, ArtifactRecord> activeByName = new ConcurrentHashMap<>();

    public synchronized ArtifactRecord record(String name, String ref, ChangeKind kind, String location) {
        int version = nextVersion(name);
        ArtifactRecord record = new ArtifactRecord(name + "-v" + version, name, ref, kind, location, version,
                System.currentTimeMillis());
        history.add(record);
        activeByName.put(name, record);
        log.info("制品入库：{}（类型={}，位置={}）", record.artifactId(), kind, location);
        return record;
    }

    public synchronized int nextVersion(String name) {
        return history.stream().filter(r -> r.name().equals(name)).mapToInt(ArtifactRecord::version).max().orElse(0) + 1;
    }

    public Optional<ArtifactRecord> active(String name) {
        return Optional.ofNullable(activeByName.get(name));
    }

    /** 上一个生效版本（回滚目标）。 */
    public Optional<ArtifactRecord> previousOf(String name) {
        List<ArtifactRecord> forName = new ArrayList<>();
        for (ArtifactRecord record : history) {
            if (record.name().equals(name)) {
                forName.add(record);
            }
        }
        if (forName.size() < 2) {
            return Optional.empty();
        }
        forName.sort(Comparator.comparingInt(ArtifactRecord::version));
        return Optional.of(forName.get(forName.size() - 2));
    }

    public synchronized void activate(String name, ArtifactRecord record) {
        activeByName.put(name, record);
        log.warn("制品指针回退：{} → {}（版本 {}）", name, record.artifactId(), record.version());
    }

    public List<ArtifactRecord> history() {
        List<ArtifactRecord> list = new ArrayList<>(history);
        list.sort(Comparator.comparingLong(ArtifactRecord::createdAtMillis).reversed());
        return list;
    }

    public List<ArtifactRecord> activeArtifacts() {
        return activeByName.values().stream()
                .sorted(Comparator.comparing(ArtifactRecord::name))
                .toList();
    }

    public synchronized void clear() {
        history.clear();
        activeByName.clear();
    }
}
