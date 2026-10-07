from dataclasses import dataclass

from shopcart.pricing import apply_discount


@dataclass
class Item:
    name: str
    price: float
    quantity: int = 1


class Cart:
    def __init__(self, items=None):
        self.items = items if items is not None else []

    def add(self, item: Item) -> None:
        for existing in self.items:
            if existing.name == item.name:
                existing.quantity += item.quantity
                return
        self.items.append(item)

    def total(self, discount_percent: float = 0) -> float:
        subtotal = sum(i.price * i.quantity for i in self.items)
        return apply_discount(subtotal, discount_percent)

    def average_price(self) -> float:
        count = sum(i.quantity for i in self.items)
        if count == 0:
            return 0.0
        return sum(i.price * i.quantity for i in self.items) / count
