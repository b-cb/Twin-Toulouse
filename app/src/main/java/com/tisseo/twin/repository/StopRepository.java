package com.tisseo.twin.repository;

import com.tisseo.twin.model.StopEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StopRepository extends JpaRepository<StopEntity, String> {

}