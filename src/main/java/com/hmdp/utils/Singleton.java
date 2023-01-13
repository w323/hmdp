package com.hmdp.utils;

public class Singleton {

    private static Singleton singleton;

    private Singleton() {

    }

    public static Singleton getSingleton() {
        if (singleton == null) {
            singleton = new Singleton();
        }
        return singleton;
    }


}

class Multiple {

    private static Singleton singleton;

    public static void main(String[] args) {
        for (int i = 0; i < 50; i++) {
            new Thread(()->{
                singleton = Singleton.getSingleton();
                System.out.println(Thread.currentThread().getName() + "\t" + singleton);
            },i+"").start();
        }
    }
}
