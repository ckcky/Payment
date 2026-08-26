package com.payment.catalog.infra.persistence.product;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 商品持久化实体（PO）：仅承载商品表列，领域规则在 {@code domain.Product}，映射由仓储完成。
 */
@TableName("products")
public class ProductEntity extends BaseEntity {

    private String productCode;
    private String name;
    private String type;
    /** 商品状态机枚举名（状态机逻辑在领域层，持久化只存枚举名）。 */
    private String status;

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
