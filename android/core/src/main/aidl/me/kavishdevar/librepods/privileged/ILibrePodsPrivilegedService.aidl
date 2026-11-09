package me.kavishdevar.librepods.privileged;

interface ILibrePodsPrivilegedService {
    boolean setMetadata(String address, int key, in byte[] value);
    boolean setConnectionPolicy(String address, int profile, int policy);
    boolean isAlive();
    void destroy();
}
