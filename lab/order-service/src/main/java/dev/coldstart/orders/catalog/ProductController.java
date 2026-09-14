package dev.coldstart.orders.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProductController {

	private final ProductCatalog catalog;

	ProductController(ProductCatalog catalog) {
		this.catalog = catalog;
	}

	@GetMapping("/api/products/{sku}")
	ProductView get(@PathVariable String sku) {
		return catalog.find(sku);
	}

}
