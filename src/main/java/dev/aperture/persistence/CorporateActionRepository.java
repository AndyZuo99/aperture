package dev.aperture.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for corporate actions. */
public interface CorporateActionRepository extends JpaRepository<CorporateActionEntity, Long> {

    Optional<CorporateActionEntity> findByDedupeKey(String dedupeKey);

    boolean existsByDedupeKey(String dedupeKey);
}
