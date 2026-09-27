package com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;

/**
 * 只暴露白名单内 skill 的 {@link SkillRegistry} 包装器。
 *
 * <p>底层仍复用完整的 {@link com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry}，
 * 对外仅返回指定名称的 skill，用于「单 skill 执行节点」按需隔离技能。</p>
 */
public class FilteredSkillRegistry implements SkillRegistry {

    private final SkillRegistry delegate;
    private final Set<String> names;

    public FilteredSkillRegistry(SkillRegistry delegate, Set<String> names) {
        this.delegate = delegate;
        this.names = names;
    }

    @Override
    public Optional<SkillMetadata> get(String name) {
        if (!names.contains(name)) {
            return Optional.empty();
        }
        return delegate.get(name);
    }

    @Override
    public List<SkillMetadata> listAll() {
        return delegate.listAll().stream()
                .filter(s -> names.contains(s.getName()))
                .toList();
    }

    @Override
    public boolean contains(String name) {
        return names.contains(name) && delegate.contains(name);
    }

    @Override
    public int size() {
        return (int) listAll().size();
    }

    @Override
    public void reload() {
        delegate.reload();
    }

    @Override
    public String readSkillContent(String name) throws IOException {
        return delegate.readSkillContent(name);
    }

    @Override
    public String getSkillLoadInstructions() {
        return delegate.getSkillLoadInstructions();
    }

    @Override
    public String getRegistryType() {
        return delegate.getRegistryType();
    }

    @Override
    public SystemPromptTemplate getSystemPromptTemplate() {
        return delegate.getSystemPromptTemplate();
    }
}