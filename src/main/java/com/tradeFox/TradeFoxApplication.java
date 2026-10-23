package com.tradeFox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TradeFoxApplication {

	public static void main(String[] args) {
		SpringApplication.run(TradeFoxApplication.class, args);
	}

}
