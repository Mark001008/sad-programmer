package com.mark.common.enums;

/**
 * <p>描述: 正则表达式枚举 </p>
 * <p>创建时间: 2024/9/18 11:32 </p>
 *
 * @author Mark
 */
public interface RegexEnum {

    /**
     * 手机号基础正则表达式
     * <p>
     * 常见的前缀包括： 130-139, 145-149, 150-159, 166, 170-171, 175-176, 185-189 (联通) 133-134, 153, 173, 177, 180-181, 189 (电信)
     * 135-139, 147, 150-152, 157-158, 178, 182-184, 187-188 (移动)
     */
    String PHONE_REGEX = "^1[3-9]\\d{9}$";
}
