package com.github.icchon;

import com.github.icchon.router.Router;
import java.util.Set;

public class RouterTest
{
    public static void main(String args[]){
        Set<Integer> marketPorts = Set.of(5001);
        Router router = new Router(5000, marketPorts);
        router.run();
    }
}

