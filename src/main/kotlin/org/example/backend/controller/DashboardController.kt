package org.example.backend.controller

import org.example.backend.model.ActivityItemDto
import org.example.backend.model.DashboardKpisDto
import org.example.backend.service.DashboardService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/dashboard")
class DashboardController(private val service: DashboardService) {

    @GetMapping("/kpis")
    fun kpis(): DashboardKpisDto = service.kpis()

    @GetMapping("/activity")
    fun activity(): List<ActivityItemDto> = service.activity()
}
