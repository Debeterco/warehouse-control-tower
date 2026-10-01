package com.fabrica.controltower;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Warehouse Control Tower (Almoxarifado Control Tower)
 * Team 5 - Supply Chain Logistics
 *
 * <p>Industrial warehouse control tower: ABC / Pareto curve, reorder point and
 * real-time stockout alerting.</p>
 */
@SpringBootApplication
public class WarehouseControlTowerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WarehouseControlTowerApplication.class, args);
    }
}