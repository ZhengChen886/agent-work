package com.cloud.alibaba.ai.example.skills.skillsagentexample.model.repository;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.model.entity.ModelProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModelProviderRepository extends JpaRepository<ModelProvider, String> {

    Optional<ModelProvider> findByActiveTrue();

    List<ModelProvider> findAllByOrderByCreatedAtAsc();

    @Transactional
    @Modifying
    @Query("update ModelProvider m set m.active = false where m.active = true")
    int deactivateAll();
}
