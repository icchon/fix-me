package com.github.icchon;

import com.github.icchon.router.Router;
import java.util.Set;

public class RouterTest
{
    public static void main(String args[]){
        Set<Integer> marketPorts = Set.of(25000, 25001);
        Router router = new Router(15000, marketPorts);
        router.run();
    }
}

