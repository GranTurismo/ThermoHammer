using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace ThermoHammer.Api.Migrations
{
    /// <inheritdoc />
    public partial class AddRunQualityAndOsBuild : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<int>(
                name: "Confidence",
                table: "Hammers",
                type: "int",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "OsBuild",
                table: "Hammers",
                type: "nvarchar(64)",
                maxLength: 64,
                nullable: true);

            migrationBuilder.AddColumn<int>(
                name: "SchemaVersion",
                table: "Hammers",
                type: "int",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "SecurityPatch",
                table: "Hammers",
                type: "nvarchar(16)",
                maxLength: 16,
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "SocModel",
                table: "Hammers",
                type: "nvarchar(64)",
                maxLength: 64,
                nullable: true);

            migrationBuilder.AddColumn<int>(
                name: "StressMode",
                table: "Hammers",
                type: "int",
                nullable: true);

            migrationBuilder.AddColumn<int>(
                name: "ValidityFlags",
                table: "Hammers",
                type: "int",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "Confidence",
                table: "Hammers");

            migrationBuilder.DropColumn(
                name: "OsBuild",
                table: "Hammers");

            migrationBuilder.DropColumn(
                name: "SchemaVersion",
                table: "Hammers");

            migrationBuilder.DropColumn(
                name: "SecurityPatch",
                table: "Hammers");

            migrationBuilder.DropColumn(
                name: "SocModel",
                table: "Hammers");

            migrationBuilder.DropColumn(
                name: "StressMode",
                table: "Hammers");

            migrationBuilder.DropColumn(
                name: "ValidityFlags",
                table: "Hammers");
        }
    }
}
