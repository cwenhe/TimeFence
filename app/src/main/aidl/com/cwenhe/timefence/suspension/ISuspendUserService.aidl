package com.cwenhe.timefence.suspension;

interface ISuspendUserService {
    String setPackageSuspended(String packageName, int userId, boolean suspended) = 1;
    void destroy() = 16777114;
}
