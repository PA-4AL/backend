package org.example.backend.controller.v1

import org.example.backend.model.ActivityItemDto
import org.example.backend.model.DashboardKpisDto
import org.example.backend.service.DashboardService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Préfixe `/api/v1` appliqué par `WebMvcConfig` — ne pas l'écrire ici. */
@RestController
@RequestMapping("/dashboard", version = "1+")
class DashboardV1Controller(private val service: DashboardService) {

    @GetMapping("/kpis")
    fun kpis(): DashboardKpisDto = service.kpis()

    @GetMapping("/activity")
    fun activity(): List<ActivityItemDto> = service.activity()
}
