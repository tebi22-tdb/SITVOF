package com.sit_titulacion.sit.repository

import com.sit_titulacion.sit.domain.CorreoPendienteGuardado
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface CorreoPendienteRepository : MongoRepository<CorreoPendienteGuardado, String>
