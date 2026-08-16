package com.remittance.account.repository;

import com.remittance.account.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, Long> {

	Optional<Account> findByAccountId(UUID accountId);
}
