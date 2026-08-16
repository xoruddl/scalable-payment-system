package com.remittance.transfer.repository;

import com.remittance.transfer.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

	Optional<Transfer> findByTransferId(UUID transferId);
}
