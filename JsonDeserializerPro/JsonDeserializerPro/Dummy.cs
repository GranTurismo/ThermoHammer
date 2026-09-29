using System;
using System.Collections.Generic;

public class Customer
{
    public int Id { get; set; }
    public string Name { get; set; }
    public string Email { get; set; }
    public bool IsActive { get; set; }
    public bool IsDeleted { get; set; }
    public decimal Balance { get; set; }
    public double Rating { get; set; }
    public double Score { get; set; }
    public string MiddleName { get; set; }          // null in JSON
    public string Note { get; set; }                // escaped characters
    public string[] Tags { get; set; }              // array -> array
    public List<int> LoginCounts { get; set; }      // array -> List<T>
    public Address Address { get; set; }            // nested object
    public List<Order> Orders { get; set; }         // array of objects
    public int[][] Matrix { get; set; }             // nested arrays
    public Dictionary<string, string> Metadata { get; set; }  // empty object

    // EXTRA property: not present in JSON, must remain null
    public string PhoneNumber { get; set; }

    // NOTE: "legacyCode" from the JSON has no matching property here
}

public class Address
{
    public string Street { get; set; }
    public string City { get; set; }
    public string PostalCode { get; set; }
    public Coordinates Coordinates { get; set; }
}

public class Coordinates
{
    public double Lat { get; set; }
    public double Lng { get; set; }
}

public class Order
{
    public string OrderId { get; set; }
    public decimal Amount { get; set; }
    public List<string> Items { get; set; }
    public bool Shipped { get; set; }
    public DateTime? DeliveredAt { get; set; }      // null or ISO-8601 string
}