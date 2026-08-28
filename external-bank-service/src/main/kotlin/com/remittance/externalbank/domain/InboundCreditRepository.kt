package com.remittance.externalbank.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface InboundCreditRepository : JpaRepository<InboundCredit, UUID>
