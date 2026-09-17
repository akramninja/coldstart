package dev.coldstart.ordersapi.order;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

	@EntityGraph(attributePaths = "customer")
	Optional<CustomerOrder> findWithCustomerById(Long id);

}
