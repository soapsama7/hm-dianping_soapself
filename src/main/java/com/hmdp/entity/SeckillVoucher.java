package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
// 上面三个注解均是lombok
// EqualsAndHashCode表示生成 equals 和 hashCode 时，不调用父类的字段
// 意思就是在判断两个对象是否相等、以及计算哈希值时，只看当前这个类自己声明的字段，不管它父类里有什么字段，但这个类没父类，所以可有可无

// Accessors表示开启链式调用

@TableName("tb_seckill_voucher")
// 告诉 MyBatis-Plus这个实体类对应哪张表。如果没有它，MyBatis-Plus 会默认SeckillVoucher → seckill_voucher

// Serializable表明这个对象可以被序列化（变成字节流）
public class SeckillVoucher implements Serializable {

    // 防止序列化版本不一致导致反序列化失败，一般写1L，不写也可以
    private static final long serialVersionUID = 1L;

    /**
     * 关联的优惠券的id
     */
    // value表示Java 字段 voucherId ↔ 数据库字段 voucher_id。
    // type表示MyBatis-Plus 主键策略，这里用的是INPUT，表明插入 tb_seckill_voucher 时，voucher_id 必须自己传
    @TableId(value = "voucher_id", type = IdType.INPUT)
    private Long voucherId;

    /**
     * 库存
     */
    private Integer stock;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 生效时间
     */
    private LocalDateTime beginTime;

    /**
     * 失效时间
     */
    private LocalDateTime endTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
