package com.tisseo.twin.repository;

import com.tisseo.twin.model.ShapeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ShapeRepository extends JpaRepository<ShapeEntity, String> {
}