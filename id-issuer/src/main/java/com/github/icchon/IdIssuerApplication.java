package com.github.icchon;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class IdIssuerApplication{
    @GetMapping("/")
    public String hello(){
        return "hello spring boot";
    }

    public static void main(String[] args){
        SpringApplication.run(IdIssuerApplication.class, args);
    }
}
